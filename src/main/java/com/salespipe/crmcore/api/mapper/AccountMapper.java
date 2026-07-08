package com.salespipe.crmcore.api.mapper;

import com.salespipe.crmcore.api.dto.AccountResponse;
import com.salespipe.crmcore.domain.Account;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    AccountResponse toResponse(Account a);
}
