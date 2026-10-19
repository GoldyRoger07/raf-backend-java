package com.raikiservice.raf.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Base des tests d'integration : contexte complet contre le MySQL local
 * (base raf_test). Chaque test cree ses propres joueurs et quiz, avec des
 * identifiants uniques : les tests ne dependent ni de l'ordre ni les uns des
 * autres, et n'ont rien a nettoyer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
public abstract class IntegrationTest {

    @Autowired
    protected TestData data;

    @Autowired
    protected TestInfrastructure.CapturingSmsSender sms;

    @Autowired
    protected TestInfrastructure.CapturingMailSender mails;

    @Autowired
    protected TestInfrastructure.CapturingPushSender pushes;

    protected static void assertApiError(ErrorCode expected, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(expected);
    }

    /**
     * Reessaie une verification jusqu'a 5 s : l'audit et les statistiques sont
     * ecrits apres la transaction, sur un autre thread.
     */
    protected static void eventually(Runnable assertion) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    /** Lance {@code count} appels au meme instant ; renvoie ceux qui ont reussi. */
    protected static <T> List<T> concurrently(int count, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return call.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException refused) {
                    // Un appel refuse (conflit) est un resultat acceptable : on compte les
                    // reussites. La cause est affichee pour diagnostiquer un refus inattendu.
                    Throwable cause = refused.getCause();
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    System.err.println("[concurrently] appel refuse : "
                            + cause.getClass().getSimpleName() + " - " + cause.getMessage());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
