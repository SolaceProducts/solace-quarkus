package com.solace.quarkus.messaging.incoming;

import com.solacesystems.jcsmp.XMLMessage;

public class SettleMetadata {

    XMLMessage.Outcome settleOutcome;

    public static SettleMetadata accepted() {
        return new SettleMetadata(XMLMessage.Outcome.ACCEPTED);
    }

    public static SettleMetadata rejected() {
        return new SettleMetadata(XMLMessage.Outcome.REJECTED);
    }

    public static SettleMetadata failed() {
        return new SettleMetadata(XMLMessage.Outcome.FAILED);
    }

    public SettleMetadata(XMLMessage.Outcome settleOutcome) {
        this.settleOutcome = settleOutcome;
    }

    public XMLMessage.Outcome getOutcome() {
        return settleOutcome;
    }
}
