package com.postech.workshop_service.api.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.postech.workshop_service.api.dtos.ErrorResponse;
import com.postech.workshop_service.application.exceptions.AcessoNegadoException;
import com.postech.workshop_service.application.exceptions.ContaInativaException;
import com.postech.workshop_service.application.exceptions.CredenciaisInvalidasException;
import com.postech.workshop_service.application.exceptions.TokenInvalidoException;
import com.postech.workshop_service.domain.exceptions.RecursoNaoEncontradoException;
import com.postech.workshop_service.domain.exceptions.RegraDeNegocioException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Locks the public HTTP error contract: status, reason phrase, message and request path. */
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ordens-servico");

	private static ErrorResponse assertError(ResponseEntity<ErrorResponse> response, int status, String message) {
		ErrorResponse body = response.getBody();
		assertEquals(status, response.getStatusCode().value());
		assertEquals(status, body.getStatus());
		assertEquals("/api/v1/ordens-servico", body.getPath());
		assertEquals(message, body.getMessage());
		assertTrue(body.getTimestamp() != null && !body.getError().isBlank());
		return body;
	}

	@Test
	void domainAndApplicationExceptionsKeepTheirMessages() {
		assertError(handler.handleBusinessException(new RegraDeNegocioException("regra"), request), 422, "regra");
		assertError(handler.handleNotFoundException(new RecursoNaoEncontradoException("ausente"), request), 404,
				"ausente");
		assertError(handler.handleAccessDenied(new AcessoNegadoException("negado"), request), 403, "negado");
		assertError(handler.handleIllegalArgumentException(new IllegalArgumentException("INVALID_CPF"), request), 400,
				"INVALID_CPF");
		for (RuntimeException unauthorized : List.of(new CredenciaisInvalidasException("credenciais"),
				new ContaInativaException("inativa"), new TokenInvalidoException("token")))
			assertError(handler.handleUnauthorized(unauthorized, request), 401, unauthorized.getMessage());
	}

	@Test
	void frameworkExceptionsDoNotLeakInternalDetails() {
		assertError(handler.handleSpringSecurityAccessDenied(new AccessDeniedException("internal rule"), request),
				403, "Usuário autenticado sem permissão para acessar este recurso.");
		assertError(handler.handleHttpMessageNotReadable(
				new HttpMessageNotReadableException("jackson detail", new MockHttpInputMessage(new byte[0])), request),
				400, "Payload invalido ou mal formado.");
		assertError(handler.handleDataAccessException(new DataAccessResourceFailureException("jdbc url"), request),
				503, "Servico de banco de dados temporariamente indisponivel. Tente novamente em alguns instantes.");
		assertError(handler.handleGenericException(new IllegalStateException("stack detail"), request), 500,
				"Ocorreu um erro interno no servidor.");
	}

	@Test
	void requestShapeErrorsNameTheOffendingInput() throws Exception {
		var mismatch = new MethodArgumentTypeMismatchException("abc", java.util.UUID.class, "id", null,
				new IllegalArgumentException());
		assertError(handler.handleMethodArgumentTypeMismatch(mismatch, request), 400,
				"Parametro 'id' tem formato invalido.");
		assertError(handler.handleMissingRequestParameter(new MissingServletRequestParameterException("placa", "String"),
				request), 400, "Parametro obrigatorio 'placa' ausente na requisicao.");
		assertError(handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.POST, "api/v1/x"), request),
				404, "Recurso não encontrado: POST /api/v1/x. Verifique a URL informada.");
		assertError(handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PATCH", Set.of("GET")),
				request), 405, "Método PATCH não suportado neste recurso. Métodos permitidos: [GET].");
		assertError(handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PATCH"), request),
				405, "Método PATCH não suportado neste recurso. Métodos permitidos: .");
	}

	@Test
	void beanValidationReturnsEveryFieldError() throws Exception {
		var binding = new BeanPropertyBindingResult(new Object(), "ordem");
		binding.addError(new FieldError("ordem", "veiculoPlaca", "obrigatoria"));
		binding.addError(new FieldError("ordem", "clienteDocumento", "invalido"));
		var parameter = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
		ErrorResponse body = assertError(
				handler.handleValidationExceptions(new MethodArgumentNotValidException(parameter, binding), request), 400,
				"Erro de validacao estrutural no payload.");
		assertEquals(List.of("veiculoPlaca:obrigatoria", "clienteDocumento:invalido"),
				body.getFieldErrors().stream().map(e -> e.getField() + ":" + e.getMessage()).toList());
		assertNull(assertError(handler.handleBusinessException(new RegraDeNegocioException("x"), request), 422, "x")
			.getFieldErrors());
	}

}
