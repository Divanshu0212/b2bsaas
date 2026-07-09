package com.salespipe.emailtracking.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One element of a SendGrid Event Webhook POST body. SendGrid delivers a JSON array of
 * these objects per request (its canonical shape — chosen here over SES's SNS-wrapped
 * notification shape as the "one canonical shape" the plan asks to pick, since SendGrid's
 * flat-array-of-events body is simpler to map 1:1 onto {@code email_events} rows without
 * an extra SNS-envelope unwrap step).
 *
 * <p>Only the fields this task's BOUNCED mapping needs are modeled; SendGrid's real
 * payload has many more (sg_event_id, sg_message_id, category, etc) — {@code
 * @JsonIgnoreProperties(ignoreUnknown = true)} tolerates them without a schema change
 * here. {@code sg_message_id}/{@code custom args} would normally carry back our own
 * {@code tracking_id} (set as a custom arg at send time); since provider integration at
 * send time is explicitly out of scope for this stub (Phase 4 concern per the plan), a
 * synthetic {@code trackingId} custom field is read directly for this task's endpoint
 * instead — see {@code WebhookController} javadoc for how a real integration would wire
 * this differently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendGridWebhookEvent {

    private String email;

    @JsonAlias("event")
    private String event;

    /**
     * Not a real SendGrid field — see class javadoc. Stands in for what a real
     * integration would carry through {@code sg_message_id}/custom args back to our
     * {@code tracking_id}.
     */
    private String trackingId;

    private String ip;

    private String useragent;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUseragent() { return useragent; }
    public void setUseragent(String useragent) { this.useragent = useragent; }
}
