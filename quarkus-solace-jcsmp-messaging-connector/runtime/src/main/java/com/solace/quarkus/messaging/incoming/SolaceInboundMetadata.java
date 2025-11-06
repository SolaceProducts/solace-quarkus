package com.solace.quarkus.messaging.incoming;

import java.io.Serializable;
import java.util.Map;

import com.solace.quarkus.messaging.converters.Converter;
import com.solace.quarkus.messaging.converters.SolaceMessageUtils;
import com.solacesystems.jcsmp.*;

public class SolaceInboundMetadata {
    private final BytesXMLMessage msg;

    public SolaceInboundMetadata(BytesXMLMessage msg) {
        this.msg = msg;
    }

    public boolean isRedelivered() {
        return msg.getRedelivered();
    }

    public boolean getMessageDiscardNotification() {
        return msg.getDiscardIndication();
    }

    public <T extends Serializable> T getAndConvertPayload(Converter.BytesToObject<T> bytesToObject, Class<T> aClass)
            throws RuntimeException {
        return getAndConvertPayload(bytesToObject, aClass, msg);
        //        return msg.getAndConvertPayload(bytesToObject, aClass);
    }

    public String getDestinationName() {
        return msg.getDestination().getName();
    }

    public long getTimeStamp() {
        return msg.getSenderTimestamp();
    }

    public boolean isCached() {
        return msg.isCacheMessage();
    }

    public ReplicationGroupMessageId getReplicationGroupMessageId() {
        return msg.getReplicationGroupMessageId();
    }

    //    public int getClassOfService() {
    //        return msg.getClassOfService();
    //    }

    public Long getSenderTimestamp() {
        return msg.getSenderTimestamp();
    }

    public String getSenderId() {
        return msg.getSenderId();
    }

    public boolean hasProperty(String s) {
        return msg.getProperties().containsKey(s);
    }

    public String getProperty(String s) {
        try {
            return msg.getProperties().getString(s);
        } catch (SDTException e) {
            throw new RuntimeException(e);
        }
    }

    public String getPayloadAsString() {
        return SolaceMessageUtils.getPayloadAsString(msg);
    }

    //    public Object getCorrelationKey() {
    //        return msg.getCorrelationKey();
    //    }

    public long getSequenceNumber() {
        return msg.getSequenceNumber();
    }

    public int getPriority() {
        return msg.getPriority();
    }

    public boolean hasContent() {
        return msg.hasContent();
    }

    public String getApplicationMessageId() {
        return msg.getApplicationMessageId();
    }

    public String getApplicationMessageType() {
        return msg.getApplicationMessageType();
    }

    public String dump() {
        return msg.dump();
    }

    //    public InteroperabilitySupport.RestInteroperabilitySupport getRestInteroperabilitySupport() {
    //        return msg.getRestInteroperabilitySupport();
    //    }

    public BytesXMLMessage getMessage() {
        return msg;
    }

    public String getPayload() {
        return SolaceMessageUtils.getPayloadAsString(msg);
    }

    public byte[] getPayloadAsBytes() {
        return SolaceMessageUtils.getPayloadAsBytes(msg);
    }

    public Object getKey() {
        return msg.getCorrelationKey();
    }

    public long getExpiration() {
        return msg.getExpiration();
    }

    public String getCorrelationId() {
        return msg.getCorrelationId();
    }

    public Map<String, String> getProperties() {
        return SolaceMessageUtils.getPropertiesMap(msg.getProperties());
    }

    public String getPartitionKey() {
        try {
            return msg.getProperties().getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
        } catch (SDTException e) {
            throw new RuntimeException(e);
        }
    }

    public <T extends Serializable> T getAndConvertPayload(Converter.BytesToObject<T> converter, Class<T> outputType,
            BytesXMLMessage solaceMessage) {
        //        Validation.nullIllegal(converter, "Converter can't be null");
        //        Validation.nullIllegal(outputType, "Output type parameter can't be null");
        //        if (converter instanceof MessageToSDTMapConverter) {
        //            MessageToSDTMapConverter sdtConverter = (MessageToSDTMapConverter)converter;
        //            return (T)sdtConverter.get(solaceMessage);
        //        } else
        if (solaceMessage instanceof BytesMessage) {
            byte[] b = ((BytesMessage) solaceMessage).getData();
            return (T) converter.convert(b);
        } else {
            throw new RuntimeException("Incompatible message, provided converter can't be used");
        }
    }

}
