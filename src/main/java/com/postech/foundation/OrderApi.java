package com.postech.foundation;

import com.postech.workshop_service.application.usecases.DadosCriacaoOrdemServico;
import com.postech.workshop_service.infrastructure.security.UsuarioAutenticadoPrincipal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/ordens-servico")
public class OrderApi {

	@org.springframework.beans.factory.annotation.Autowired
	private Saga saga;

	@PostMapping("/{id}/saga/retomar")
	@PreAuthorize("hasRole('ADMINISTRADOR')")
	public ResponseEntity<?> resumeSaga(@PathVariable UUID id) {
		saga.resume(id);
		return ResponseEntity.accepted().body(orders.view(id));
	}

	@PostMapping("/{id}/cancelar")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE')")
	public ResponseEntity<?> cancel(@PathVariable UUID id) {
		saga.cancel(id, "ATENDIMENTO_SOLICITOU_CANCELAMENTO");
		return ResponseEntity.accepted().body(orders.view(id));
	}

	@GetMapping("/{id}/saga")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE')")
	public Map<String, Object> saga(@PathVariable UUID id) {
		return Map.of("progresso", db.queryForList("SELECT * FROM saga_details WHERE os_id=?", id), "historico",
				db.queryForList(
						"SELECT event_type,detail,occurred_at FROM saga_audit WHERE os_id=? ORDER BY occurred_at,id",
						id));
	}

	private final Orders orders;

	private final JdbcTemplate db;

	public OrderApi(Orders orders, JdbcTemplate db) {
		this.orders = orders;
		this.db = db;
	}

	@PostMapping
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE')")
	public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") UUID key,
			@RequestBody DadosCriacaoOrdemServico request) {
		return ResponseEntity.accepted().body(orders.create(key, request));
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','MECANICO','CLIENTE')")
	public Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticadoPrincipal actor) {
		var view = orders.view(id);
		authorize(view, actor);
		return view;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','MECANICO','CLIENTE')")
	public List<Map<String, Object>> list(@RequestParam(defaultValue = "0") int pagina,
			@RequestParam(defaultValue = "20") int tamanho,
			@AuthenticationPrincipal UsuarioAutenticadoPrincipal actor) {
		if (pagina < 0 || pagina > 100000 || tamanho < 1 || tamanho > 100)
			throw new IllegalArgumentException("INVALID_PAGE");
		boolean staff = staff(actor);
		if (!staff && actor.getClienteId() == null)
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		var ids = staff
				? db.queryForList("SELECT os_id FROM os_workflow ORDER BY updated_at DESC,os_id LIMIT ? OFFSET ?",
						UUID.class, tamanho, pagina * tamanho)
				: db.queryForList(
						"SELECT w.os_id FROM os_workflow w JOIN ordens_servico o ON o.id=w.os_id WHERE o.id_cliente=? ORDER BY w.updated_at DESC,w.os_id LIMIT ? OFFSET ?",
						UUID.class, actor.getClienteId(), tamanho, pagina * tamanho);
		return ids.stream().map(orders::view).toList();
	}

	@GetMapping({ "/{id}/historico", "/{id}/historico-status" })
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','MECANICO','CLIENTE')")
	public List<Map<String, Object>> history(@PathVariable UUID id,
			@AuthenticationPrincipal UsuarioAutenticadoPrincipal actor) {
		authorize(orders.view(id), actor);
		return db.queryForList(
				"SELECT status_anterior,status_novo,data_transicao FROM historico_status_os WHERE ordem_servico_id=? ORDER BY data_transicao,id",
				id);
	}

	@GetMapping("/{numero}/status")
	public Map<String, Object> publicStatus(@PathVariable String numero) {
		var rows = db.queryForList(
				"SELECT o.numero,w.stage AS status,w.updated_at AS ultima_atualizacao FROM ordens_servico o JOIN os_workflow w ON w.os_id=o.id WHERE o.numero=?",
				numero);
		if (rows.isEmpty())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return rows.getFirst();
	}

	@PostMapping("/{id}/itens")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','MECANICO')")
	public Map<String, Object> add(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
			@RequestBody Orders.ItemRequest request) {
		return orders.addItem(id, key, request);
	}

	@PostMapping("/{id}/composicao/encerrar")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','MECANICO')")
	public ResponseEntity<?> close(@PathVariable UUID id) {
		return ResponseEntity.accepted().body(orders.closeComposition(id));
	}

	@PostMapping("/{id}/entregar")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE')")
	public Map<String, Object> deliver(@PathVariable UUID id) {
		return orders.deliver(id);
	}

	static void authorize(Map<String, Object> view, UsuarioAutenticadoPrincipal actor) {
		if (!staff(actor) && !Objects.equals(view.get("clienteId"), actor.getClienteId()))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
	}

	private static boolean staff(UsuarioAutenticadoPrincipal actor) {
		return actor.getAuthorities()
			.stream()
			.anyMatch(a -> Set.of("ROLE_ADMINISTRADOR", "ROLE_ATENDENTE", "ROLE_MECANICO").contains(a.getAuthority()));
	}

}
