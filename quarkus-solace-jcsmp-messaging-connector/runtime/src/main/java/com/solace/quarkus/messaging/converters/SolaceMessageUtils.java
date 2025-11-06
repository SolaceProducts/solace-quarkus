package com.solace.quarkus.messaging.converters;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.solacesystems.jcsmp.*;

public class SolaceMessageUtils {
    private static final byte[] ZERO_BYTES = new byte[0];

    public static String getPayloadAsString(BytesXMLMessage solaceMessage) {
        if (solaceMessage.hasContent() && solaceMessage.hasAttachment()) {
            throw new RuntimeException("multiple payloads");
        } else if (!solaceMessage.hasAttachment()) {
            byte[] bytesPayload = solaceMessage.getBytes();
            return bytesPayload == null ? "" : new String(bytesPayload, StandardCharsets.UTF_8);
        } else if (solaceMessage instanceof TextMessage) {
            return ((TextMessage) solaceMessage).getText();
        } else if (solaceMessage instanceof XMLContentMessage) {
            return ((XMLContentMessage) solaceMessage).getAttachmentByteBuffer() == null ? ""
                    : new String(((XMLContentMessage) solaceMessage).getAttachmentByteBuffer().array(), StandardCharsets.UTF_8);
        } else if (solaceMessage instanceof BytesMessage) {
            BytesMessage bytesMessage = (BytesMessage) solaceMessage;
            byte[] bytesPayload = bytesMessage.getData();
            return bytesPayload == null ? "" : new String(bytesPayload, StandardCharsets.UTF_8);
        } else if (!(solaceMessage instanceof MapMessage)) {
            return solaceMessage.getAttachmentByteBuffer() == null ? ""
                    : new String(solaceMessage.getAttachmentByteBuffer().array(), StandardCharsets.UTF_8);
        } else {
            MapMessage mapMessage = (MapMessage) solaceMessage;
            SDTMap mapPayload = mapMessage.getMap();
            if (mapPayload == null) {
                return "";
            }

            return mapPayload.toString();
        }
    }

    public static byte[] getPayloadAsBytes(BytesXMLMessage solaceMessage) {
        if (solaceMessage.getContentLength() > 0 && solaceMessage.hasAttachment()) {
            throw new RuntimeException("multiple payloads");
        } else if (solaceMessage.hasAttachment()) {
            if (solaceMessage instanceof TextMessage) {
                String stringPayload = ((TextMessage) solaceMessage).getText();
                return stringPayload != null ? stringPayload.getBytes(StandardCharsets.UTF_8) : ZERO_BYTES;
            } else if (solaceMessage instanceof XMLContentMessage) {
                return ((XMLContentMessage) solaceMessage).getAttachmentByteBuffer() == null ? ZERO_BYTES
                        : ((XMLContentMessage) solaceMessage).getAttachmentByteBuffer().array();
            } else if (solaceMessage instanceof BytesMessage) {
                return ((BytesMessage) solaceMessage).getData();
            } else if (solaceMessage instanceof MapMessage) {
                return ZERO_BYTES;
            } else {
                return solaceMessage.getAttachmentByteBuffer() == null ? ZERO_BYTES
                        : solaceMessage.getAttachmentByteBuffer().array();
            }
        } else {
            return solaceMessage.getBytes();
        }
    }

    public static Map<String, String> getPropertiesMap(SDTMap sdtMap) {
        Map<String, String> properties = new HashMap<>();
        sdtMap.keySet().forEach(key -> {
            try {
                properties.put(key, sdtMap.getString(key));
            } catch (SDTException e) {
                throw new RuntimeException(e);
            }
        });

        return properties;
    }
}
