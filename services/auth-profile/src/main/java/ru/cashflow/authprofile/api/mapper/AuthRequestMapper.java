package ru.cashflow.authprofile.api.mapper;

import org.mapstruct.Mapper;

import ru.cashflow.authprofile.api.dto.request.RequestCodeRq;
import ru.cashflow.authprofile.api.dto.request.VerifyCodeRq;
import ru.cashflow.authprofile.config.BaseMapperConfig;
import ru.cashflow.authprofile.features.security.command.model.RequestCodeData;
import ru.cashflow.authprofile.features.security.command.model.VerifyCodeData;

@Mapper(config = BaseMapperConfig.class)
public interface AuthRequestMapper {

    RequestCodeData convert(RequestCodeRq request, String source);

    VerifyCodeData convert(VerifyCodeRq request, String source);
}
