package ru.cashflow.authprofile.api.controller.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.authprofile.api.controller.ProfileController;
import ru.cashflow.authprofile.api.dto.request.UpdateProfileRq;
import ru.cashflow.authprofile.api.dto.response.ProfileRs;
import ru.cashflow.authprofile.api.mapper.ProfileMapper;
import ru.cashflow.authprofile.api.security.CurrentUser;
import ru.cashflow.authprofile.features.profile.application.service.ProfileManagementApplicationService;
import ru.cashflow.authprofile.features.profile.query.service.ProfileQueryService;

@RestController
@RequiredArgsConstructor
public class ProfileControllerImpl implements ProfileController {

    private final ProfileQueryService queries;
    private final ProfileManagementApplicationService management;
    private final ProfileMapper mapper;

    @Override
    public ProfileRs getMe(CurrentUser user) {
        return mapper.convert(queries.getByUserId(user.userId()));
    }

    @Override
    public ProfileRs updateMe(UpdateProfileRq request, CurrentUser user) {
        return mapper.convert(management.update(mapper.convert(request, user.userId())));
    }
}
