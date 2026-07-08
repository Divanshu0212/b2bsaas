package com.salespipe.crmcore.api.mapper;

import com.salespipe.crmcore.api.dto.ContactResponse;
import com.salespipe.crmcore.domain.Contact;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ContactMapper {
    ContactResponse toResponse(Contact c);
}
