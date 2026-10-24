package ru.cashflow.authprofile.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import ru.cashflow.authprofile.api.dto.response.CodeChallengeRs;
import ru.cashflow.authprofile.config.BaseMapperConfig;
import ru.cashflow.authprofile.features.security.command.model.CodeChallengeResult;
import ru.cashflow.authprofile.features.security.constants.SecurityConstants;

@Mapper(config = BaseMapperConfig.class, imports = SecurityConstants.class)
public interface CodeChallengeMapper {

    @Mapping(target = "codeLength", expression = "java(SecurityConstants.OTP_CODE_LENGTH)")
    CodeChallengeRs convert(CodeChallengeResult source);
}
