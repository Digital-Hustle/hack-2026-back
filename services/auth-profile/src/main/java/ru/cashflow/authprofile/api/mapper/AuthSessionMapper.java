package ru.cashflow.authprofile.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import ru.cashflow.authprofile.api.dto.response.AuthSessionRs;
import ru.cashflow.authprofile.api.dto.response.SessionRs;
import ru.cashflow.authprofile.api.dto.response.SessionsRs;
import ru.cashflow.authprofile.config.BaseMapperConfig;
import ru.cashflow.authprofile.features.security.command.model.AuthenticatedSession;
import ru.cashflow.authprofile.features.security.query.model.SessionInfo;

import java.util.List;

@Mapper(config = BaseMapperConfig.class, uses = ProfileMapper.class)
public interface AuthSessionMapper {

    @Mapping(target = "sessionId", source = "tokens.sessionId")
    @Mapping(target = "accessExpiresAt", source = "tokens.accessExpiresAt")
    @Mapping(target = "refreshExpiresAt", source = "tokens.refreshExpiresAt")
    AuthSessionRs convert(AuthenticatedSession source);

    SessionRs convert(SessionInfo source);

    default SessionsRs convert(List<SessionInfo> source) {
        var items = source.stream().map(this::convert).toList();

        return SessionsRs.builder().items(items).total(items.size()).build();
    }
}
