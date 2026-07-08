package com.salespipe.crmcore.api.mapper;

import com.salespipe.crmcore.api.dto.LeadResponse;
import com.salespipe.crmcore.domain.Lead;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface LeadMapper {
    LeadResponse toResponse(Lead lead);
}
