package com.dart.util;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionUtilsTest {

    @Test
    void afterCommit_noActiveSynchronization_runsImmediately() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        AtomicBoolean ran = new AtomicBoolean(false);
        TransactionUtils.afterCommit(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void afterCommit_activeSynchronization_waitsForAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            TransactionUtils.afterCommit(() -> ran.set(true));
            assertThat(ran).isFalse();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(ran).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
