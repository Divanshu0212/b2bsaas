package com.salespipe.notification.infra.email;

/**
 * A transactional email to send (T4.4). Deliberately minimal — the notification module
 * only sends plain-text owner alerts (hot lead, deal stage change), not marketing mail.
 */
public record EmailMessage(String toEmail, String subject, String body) {}
