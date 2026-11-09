package com.postech.foundation;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class SagaScheduler {

	private final Saga saga;

	public SagaScheduler(Saga saga) {
		this.saga = saga;
	}

	@Scheduled(fixedDelayString = "${saga.poll-ms:30000}")
	public void tick() {
		saga.retryOne();
	}

}
