package com.solace.quarkus.messaging.fault;

import com.solace.quarkus.messaging.PublishReceipt;
import com.solace.quarkus.messaging.i18n.SolaceLogging;
import com.solace.quarkus.messaging.incoming.SolaceInboundMessage;
import com.solacesystems.jcsmp.*;

import io.smallrye.mutiny.Uni;

class SolaceErrorTopicPublisherHandler {
    private XMLMessageProducer publisher;
    private final OutboundErrorMessageMapper outboundErrorMessageMapper;
    private final JCSMPSession solace;

    public SolaceErrorTopicPublisherHandler(JCSMPSession solace) {
        this.solace = solace;
        outboundErrorMessageMapper = new OutboundErrorMessageMapper();
    }

    public Uni<Object> handle(SolaceInboundMessage<?> message,
            String errorTopic,
            boolean dmqEligible, Long timeToLive) {
        BytesXMLMessage outboundMessage = outboundErrorMessageMapper.mapError(
                message.getMessage(),
                dmqEligible, timeToLive);
        //        }
        return Uni.createFrom().<Object> emitter(e -> {
            try {
                publisher = solace.createProducer(new ProducerFlowProperties(), new PublishReceipt());
                // always wait for error message publish receipt to ensure it is successfully spooled on broker.
                outboundMessage.setCorrelationKey(e);
                publisher.send(outboundMessage, JCSMPFactory.onlyInstance().createTopic(errorTopic));
            } catch (Exception t) {
                e.fail(t);
            }
        }).onItem().invoke(publisher::close).onFailure().invoke(t -> {
            SolaceLogging.log.publishException(errorTopic, t);
            publisher.close();
        });
    }
}
