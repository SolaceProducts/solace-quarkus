package com.solace.quarkus.messaging.fault;

import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Metadata;

import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SettleMetadata;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.XMLMessage;

import io.smallrye.mutiny.Uni;

public class SolaceDiscard implements SolaceFailureHandler {
    private final String channel;

    public SolaceDiscard(String channel) {
        this.channel = channel;
    }

    @Override
    public CompletionStage<Void> handle(SolaceInboundMessage<?> msg, Throwable reason, Metadata metadata) {
        XMLMessage.Outcome outcome;
        if (metadata != null) {
            outcome = metadata.get(SettleMetadata.class)
                    .map(SettleMetadata::getOutcome)
                    .orElseGet(() -> XMLMessage.Outcome.REJECTED /* TODO get outcome from reason */);
        } else {
            outcome = XMLMessage.Outcome.REJECTED;
        }

        SolaceLogging.log.messageSettled(channel, outcome.toString().toLowerCase(), reason.getMessage());
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    try {
                        msg.getMessage().settle(outcome);
                    } catch (JCSMPException e) {
                        throw new RuntimeException(e);
                    }
                })
                .runSubscriptionOn(msg::runOnMessageContext)
                .subscribeAsCompletionStage();
    }
}
