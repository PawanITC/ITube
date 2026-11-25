package ITubeBackend;

import ITubeBackend.controller.HistoryController;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.engagement.WatchHistoryStore;
import ITubeBackend.engagement.memory.InMemoryEngagementStores;
import ITubeBackend.support.FakeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.AuthenticationConfig;
import ITubeBackend.security.SignedInUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Import(FakeCatalog.Config.class)
class HistoryApiTests {
    private static final AtomicLong OWNERS = new AtomicLong(7000);
    @Autowired private HistoryController controller;
    @Autowired private ITubeBackend.controller.EngagementOperationController operationController;
    @Autowired private WatchHistoryStore repository;
    // catalog-service is another process; video details come from this in-memory stand-in
    @Autowired private FakeCatalog catalog;
    @Autowired private AccessTokens tokens;
    private MockMvc mvc;
    private RequestPostProcessor viewer;
    private RequestPostProcessor other;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller, operationController)
                .addFilters(new AuthenticationConfig.AuthenticationFilter(tokens)).build();
        // History is in the in-memory engagement store (ScyllaDB in production), not the rolled-back database
        ((InMemoryEngagementStores.Resettable) repository).reset();
        catalog.reset();
        viewer = session(901L);
        other = session(902L);
    }

    @Test
    void historyReturnsRealVideoMetadataAndReflectsThumbnailChanges() throws Exception {
        var fixture = video(VideoVisibility.PUBLIC, true);
        long videoId = fixture.video().getVideoId();
        add(viewer, videoId);
        mvc.perform(get("/api/history").with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].videoId").value(videoId))
                .andExpect(jsonPath("$[0].video.videoId").value(videoId))
                .andExpect(jsonPath("$[0].video.title").value("A real watched video"))
                .andExpect(jsonPath("$[0].video.channelName").value("History test channel"))
                .andExpect(jsonPath("$[0].video.thumbnailUrl").value("/api/videos/" + videoId + "/thumbnail"));
        // In production the engagement service caches summaries for 30 s, so a change shows up within that
        fixture.video().setTitle("Updated title").setThumbnail(false);
        mvc.perform(get("/api/history").with(viewer))
                .andExpect(jsonPath("$[0].video.title").value("Updated title"))
                .andExpect(jsonPath("$[0].video.thumbnailUrl").isEmpty());
    }

    @Test
    void privateMetadataIsOnlyVisibleToTheOwnerAndMissingVideosStayRemovable() throws Exception {
        var fixture = video(VideoVisibility.PRIVATE, true);
        add(viewer, fixture.video().getVideoId());
        add(viewer, Long.MAX_VALUE);
        mvc.perform(get("/api/history").with(viewer)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].video").isEmpty()).andExpect(jsonPath("$[1].video").isEmpty());
        var owner = session(fixture.ownerId());
        add(owner, fixture.video().getVideoId());
        mvc.perform(get("/api/history").with(owner))
                .andExpect(jsonPath("$[0].video.title").value("A real watched video"))
                .andExpect(jsonPath("$[0].video.videoUrl").isEmpty());
    }

    @Test
    void unlistedAndMembersOnlyMetadataUseTheExistingPlaybackRules() throws Exception {
        var unlisted = video(VideoVisibility.UNLISTED, false);
        var members = video(VideoVisibility.MEMBERS_ONLY, true);
        add(viewer, unlisted.video().getVideoId());
        add(viewer, members.video().getVideoId());
        mvc.perform(get("/api/history").with(viewer))
                .andExpect(jsonPath("$[0].video.visibility").value("MEMBERS_ONLY"))
                .andExpect(jsonPath("$[0].video.videoUrl").isEmpty())
                .andExpect(jsonPath("$[1].video.visibility").value("UNLISTED"))
                .andExpect(jsonPath("$[1].video.videoUrl").value("/api/videos/" + unlisted.video().getVideoId() + "/file"));
    }

    @Test
    void savedHistoryUsesSessionIdentityAndIsReturnedOnlyForItsUser() throws Exception {
        // A supplied userId cannot redirect writes or reads to someone else's history.
        mvc.perform(post("/api/history?userId=902").with(viewer)
                .contentType("application/json").content("{\"videoId\":25}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.operationId").isString())
                .andExpect(jsonPath("$.state").value("PENDING"));
        mvc.perform(get("/api/history?userId=902").with(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/history?userId=901").with(other))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertEquals(1, repository.page(901L, 0, 20).size());
    }

    @Test
    void allEndpointsRequireSessionEvenWithUserIdParameter() throws Exception {
        mvc.perform(get("/api/history?userId=901")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/history?userId=901").contentType("application/json").content("{\"videoId\":25}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/history?userId=901")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/history/1?userId=901")).andExpect(status().isUnauthorized());
    }

    @Test
    void historyStillLoadsWithoutVideoDetailsWhenTheCatalogIsDown() throws Exception {
        long videoId = video(VideoVisibility.PUBLIC, true).video().getVideoId();
        add(viewer, videoId);
        catalog.setDown(true);
        // Graceful degradation: the user's own history stays available, just without titles and thumbnails
        mvc.perform(get("/api/history").with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].videoId").value(videoId))
                .andExpect(jsonPath("$[0].video").isEmpty());
        // ...and recording a view still works
        add(viewer, videoId);
        catalog.setDown(false);
        mvc.perform(get("/api/history").with(viewer))
                .andExpect(jsonPath("$[0].video.title").value("A real watched video"));
    }

    @Test
    void historyIsPaginatedNewestFirstWithIdAsTieBreaker() throws Exception {
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-01-02T10:00:00Z");
        var entries = List.of(repository.add(901L, 11L, newer), repository.add(901L, 12L, older),
                repository.add(901L, 13L, newer));
        mvc.perform(get("/api/history?page=0&size=2").with(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(entries.get(2).id().toString()))
                .andExpect(jsonPath("$[1].id").value(entries.get(0).id().toString()));
        mvc.perform(get("/api/history?page=1&size=2").with(viewer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].videoId").value(12));
        mvc.perform(get("/api/history?page=2&size=2").with(viewer)).andExpect(content().json("[]"));
    }

    @Test
    void invalidInputDoesNotCreateRecords() throws Exception {
        for (String json : List.of("{}", "{\"videoId\":0}", "{\"videoId\":-1}", "{\"videoId\":null}", "{broken")) {
            mvc.perform(post("/api/history").with(viewer).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest());
        }
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/history?" + query).with(viewer)).andExpect(status().isBadRequest());
        }
        mvc.perform(delete("/api/history/0").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/history").with(viewer)).andExpect(content().json("[]"));
    }

    @Test
    void repeatWatchesCreateSeparateHistoryEntries() throws Exception {
        add(viewer, 25L);
        add(viewer, 25L);
        mvc.perform(get("/api/history").with(viewer)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deletionChecksOwnerAndRemovesOnlyTheSelectedEntry() throws Exception {
        String id = add(viewer, 25L);
        add(viewer, 26L);
        mvc.perform(delete("/api/history/" + id + "?userId=901").with(other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/history/" + id).with(viewer)).andExpect(status().isAccepted());
        mvc.perform(get("/api/history").with(viewer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].videoId").value(26));
        mvc.perform(delete("/api/history/" + id).with(viewer)).andExpect(status().isNotFound());
    }

    @Test
    void clearingHistoryAffectsOnlyTheSessionUser() throws Exception {
        add(viewer, 25L);
        add(viewer, 26L);
        add(other, 27L);
        mvc.perform(delete("/api/history?userId=902").with(viewer)).andExpect(status().isAccepted());
        mvc.perform(get("/api/history").with(viewer)).andExpect(content().json("[]"));
        mvc.perform(get("/api/history").with(other)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/history").with(viewer)).andExpect(status().isAccepted());
    }

    private String add(RequestPostProcessor session, Long videoId) throws Exception {
        String body = mvc.perform(post("/api/history").with(session).contentType("application/json")
                .content("{\"videoId\":" + videoId + "}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String operationId = new ObjectMapper().readTree(body).get("operationId").asString();
        String receipt = mvc.perform(get("/api/engagement/operations/" + operationId).with(session))
                .andExpect(jsonPath("$.state").value("SUCCEEDED")).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(receipt).get("result").get("id").asString();
    }

    /** Requests signed in as this user (a real access token, verified by the authentication filter). */
    private RequestPostProcessor session(Long userId) {
        String token = tokens.issue(new SignedInUser(userId, null, null));
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private record HistoryVideoFixture(FakeCatalog.Video video, Long ownerId) {}

    private HistoryVideoFixture video(VideoVisibility visibility, boolean thumbnail) {
        Long ownerId = OWNERS.incrementAndGet();
        FakeCatalog.Video video = catalog.add(ownerId, "A real watched video", visibility)
                .setChannelName("History test channel").setThumbnail(thumbnail);
        return new HistoryVideoFixture(video, ownerId);
    }
}
