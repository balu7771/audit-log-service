package com.persistent.auditlog.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaginatedAuditEventsResponse {

    @JsonProperty("content")
    private List<AuditEventResponse> content;

    @JsonProperty("pageable")
    private PageableInfo pageable;

    public static PaginatedAuditEventsResponse fromPage(Page<AuditEventResponse> page) {
        return PaginatedAuditEventsResponse.builder()
            .content(page.getContent())
            .pageable(PageableInfo.from(page))
            .build();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PageableInfo {
        @JsonProperty("pageNumber")
        private int pageNumber;

        @JsonProperty("pageSize")
        private int pageSize;

        @JsonProperty("totalElements")
        private long totalElements;

        @JsonProperty("totalPages")
        private int totalPages;

        @JsonProperty("first")
        private boolean first;

        @JsonProperty("last")
        private boolean last;

        public static PageableInfo from(Page<?> page) {
            return PageableInfo.builder()
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
        }
    }
}
