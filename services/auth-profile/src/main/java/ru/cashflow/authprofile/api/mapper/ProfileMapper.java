package ru.cashflow.authprofile.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import ru.cashflow.authprofile.api.dto.request.UpdateProfileRq;
import ru.cashflow.authprofile.api.dto.response.ProfileRs;
import ru.cashflow.authprofile.config.BaseMapperConfig;
import ru.cashflow.authprofile.features.profile.api.ProfileSnapshot;
import ru.cashflow.authprofile.features.profile.command.model.UpdateProfileData;
import ru.cashflow.authprofile.features.profile.model.UserProfile;

import java.util.UUID;

@Mapper(config = BaseMapperConfig.class)
public interface ProfileMapper {

    ProfileRs convert(UserProfile source);

    ProfileRs convert(ProfileSnapshot source);

    @Mapping(target = "userId", source = "userId")
    UpdateProfileData convert(UpdateProfileRq request, UUID userId);
}
