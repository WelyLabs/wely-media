package com.calendar.media.application.mappers;

import com.calendar.media.application.dtos.StoredMediaResponse;
import com.calendar.media.domain.models.StoredMedia;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MediaDtoMapper {

    StoredMediaResponse toResponse(StoredMedia media);
}
