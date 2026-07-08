package com.salespipe.crmcore.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "contacts")
public class Contact extends TenantEntity {
    @Id private UUID id;
    @Column(name = "account_id") private UUID accountId;
    @Column(name = "first_name") private String firstName;
    @Column(name = "last_name") private String lastName;
    @Column(columnDefinition = "citext") private String email;
    private String phone;
    private String title;

    protected Contact() {}
    public Contact(UUID id, UUID orgId) { this.id = id; this.orgId = orgId; }
    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID a) { this.accountId = a; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String v) { this.firstName = v; }
    public String getLastName() { return lastName; }
    public void setLastName(String v) { this.lastName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
}
