package com.solace.quarkus.messaging.fault;

import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solacesystems.jcsmp.*;

class OutboundErrorMessageMapper {

    public BytesXMLMessage mapError(BytesXMLMessage inputMessage,
            boolean dmqEligible, Long timeToLive) {
        BytesXMLMessage outboundMessage = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
        outboundMessage.setDMQEligible(dmqEligible);
        if (timeToLive != null) {
            outboundMessage.setTimeToLive(timeToLive);
        }
        outboundMessage.writeAttachment(SolaceMessageUtils.getPayloadAsBytes(inputMessage));
        outboundMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        return outboundMessage;
    }
}
