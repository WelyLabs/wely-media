package com.calendar.media.application.mappers;

import com.calendar.media.application.dtos.StoredMediaResponse;
import com.calendar.media.domain.models.StoredMedia;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class MediaDtoMapperTest {

    private final MediaDtoMapper mapper = Mappers.getMapper(MediaDtoMapper.class);

    @Test
    void toResponse_shouldCopyEveryField() {
        assertThat(mapper.toResponse(new StoredMedia("chat/c1/m1.jpg", 1600, 1200, "image/jpeg")))
                .isEqualTo(new StoredMediaResponse("chat/c1/m1.jpg", 1600, 1200, "image/jpeg"));
    }

    @Test
    void toResponse_shouldAnswerNull_forNull() {
        assertThat(mapper.toResponse(null)).isNull();
    }
}
