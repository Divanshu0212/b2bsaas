package com.salespipe.emailtracking.infra;

import com.salespipe.emailtracking.domain.EmailEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailEventRepository extends JpaRepository<EmailEvent, EmailEvent.EmailEventId> {}
