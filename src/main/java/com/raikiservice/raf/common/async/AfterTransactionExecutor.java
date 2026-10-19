package com.raikiservice.raf.common.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ecritures annexes (audit, statistiques) executees apres la transaction qui les
 * declenche, sur un thread a part.
 *
 * <p><strong>Pourquoi un thread a part.</strong> Ecrire ces traces dans une
 * transaction imbriquee (REQUIRES_NEW) oblige le thread de la requete a tenir deux
 * connexions a la fois. Avec un pool de 10, une dizaine d'ecritures simultanees
 * suffisent : chacune tient la sienne et attend la seconde, tout se fige 30 s. Meme
 * en fin de transaction, les callbacks Spring s'executent <em>avant</em> la
 * restitution de la connexion. Ici, le thread de la requete se contente de deposer
 * la tache et rend sa connexion ; la tache prendra la suivante qui se libere.
 *
 * <p>Contrepartie : une trace en file d'attente est perdue si le processus meurt a
 * cet instant. L'arret normal, lui, vide la file.
 *
 * <p>Cette instance sert a l'audit et aux statistiques. Les notifications ont la
 * leur ({@code NotificationExecutor}) : un envoi de masse lent ne doit pas retarder
 * les traces d'audit.
 */
@Slf4j
@Primary
@Component
public class AfterTransactionExecutor implements DisposableBean {

    private final ThreadPoolExecutor executor;

    public AfterTransactionExecutor() {
        this("after-tx-", 2, 10_000);
    }

    protected AfterTransactionExecutor(String threadPrefix, int threads, int queueCapacity) {
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, threadPrefix + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Apres la fin de la transaction courante, qu'elle soit validee ou annulee :
     * c'est ce qu'il faut a l'audit, qui doit garder la trace d'une tentative
     * echouee. Sans transaction en cours, la tache s'execute tout de suite.
     */
    public void afterCompletion(String label, Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runNow(label, task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                submit(label, task);
            }
        });
    }

    /** Seulement si la transaction courante est validee. */
    public void afterCommit(String label, Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runNow(label, task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(label, task);
            }
        });
    }

    private void submit(String label, Runnable task) {
        try {
            executor.execute(() -> runNow(label, task));
        } catch (RejectedExecutionException e) {
            log.error("File des ecritures differees pleine : '{}' abandonnee", label);
        }
    }

    private void runNow(String label, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Ecriture differee '{}' en echec", label, e);
        }
    }

    /** Arret propre : les traces deja en file sont ecrites avant la fermeture. */
    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            log.error("{} ecriture(s) differee(s) perdue(s) a l'arret",
                    executor.shutdownNow().size());
        }
    }
}
