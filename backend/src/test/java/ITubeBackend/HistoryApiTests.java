package ITubeBackend;

import ITubeBackend.controller.HistoryController;
import ITubeBackend.entities.WatchHistory;
import ITubeBackend.entities.AccountEntity;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoVisibility;
import jakarta.persistence.EntityManager;
import ITubeBackend.repository.HistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Transactional
class HistoryApiTests {
    @Autowired private HistoryController controller;
    @Autowired private HistoryRepository repository;
    @Autowired private EntityManager entityManager;
    private MockMvc mvc;
    private MockHttpSession viewer;
    private MockHttpSession other;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        viewer = session(901L);
        other = session(902L);
    }

    @Test
    void historyReturnsRealVideoMetadataAndReflectsThumbnailChanges() throws Exception {
        var fixture = video(VideoVisibility.PUBLIC, true);
        long videoId = fixture.video().getVideoId();
        add(viewer, videoId);
        mvc.perform(get("/api/history").session(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].videoId").value(videoId))
                .andExpect(jsonPath("$[0].video.videoId").value(videoId))
                .andExpect(jsonPath("$[0].video.title").value("A real watched video"))
                .andExpect(jsonPath("$[0].video.channelName").value("History test channel"))
                .andExpect(jsonPath("$[0].video.thumbnailUrl").value("/api/videos/" + videoId + "/thumbnail"));
        fixture.video().setTitle("Updated title");
        fixture.video().setThumbnail(null);
        entityManager.flush();
        mvc.perform(get("/api/history").session(viewer))
                .andExpect(jsonPath("$[0].video.title").value("Updated title"))
                .andExpect(jsonPath("$[0].video.thumbnailUrl").isEmpty());
    }

    @Test
    void privateMetadataIsOnlyVisibleToTheOwnerAndMissingVideosStayRemovable() throws Exception {
        var fixture = video(VideoVisibility.PRIVATE, true);
        add(viewer, fixture.video().getVideoId());
        add(viewer, Long.MAX_VALUE);
        mvc.perform(get("/api/history").session(viewer)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].video").isEmpty()).andExpect(jsonPath("$[1].video").isEmpty());
        var owner = session(fixture.ownerId());
        add(owner, fixture.video().getVideoId());
        mvc.perform(get("/api/history").session(owner))
                .andExpect(jsonPath("$[0].video.title").value("A real watched video"))
                .andExpect(jsonPath("$[0].video.videoUrl").isEmpty());
    }

    @Test
    void unlistedAndMembersOnlyMetadataUseTheExistingPlaybackRules() throws Exception {
        var unlisted = video(VideoVisibility.UNLISTED, false);
        var members = video(VideoVisibility.MEMBERS_ONLY, true);
        add(viewer, unlisted.video().getVideoId());
        add(viewer, members.video().getVideoId());
        mvc.perform(get("/api/history").session(viewer))
                .andExpect(jsonPath("$[0].video.visibility").value("MEMBERS_ONLY"))
                .andExpect(jsonPath("$[0].video.videoUrl").isEmpty())
                .andExpect(jsonPath("$[1].video.visibility").value("UNLISTED"))
                .andExpect(jsonPath("$[1].video.videoUrl").value("/api/videos/" + unlisted.video().getVideoId() + "/file"));
    }

    @Test
    void savedHistoryUsesSessionIdentityAndIsReturnedOnlyForItsUser() throws Exception {
        // A supplied userId cannot redirect writes or reads to someone else's history.
        mvc.perform(post("/api/history?userId=902").session(viewer)
                .contentType("application/json").content("{\"videoId\":25}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.videoId").value(25))
                .andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.watchedAt").isString());
        mvc.perform(get("/api/history?userId=902").session(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/history?userId=901").session(other))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertEquals(1, repository.findByUserIdOrderByWatchedAtDescIdDesc("901",
                org.springframework.data.domain.PageRequest.of(0, 20)).size());
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
    void historyIsPaginatedNewestFirstWithIdAsTieBreaker() throws Exception {
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-01-02T10:00:00Z");
        var entries = repository.saveAll(List.of(new WatchHistory("901", 11L, newer),
                new WatchHistory("901", 12L, older), new WatchHistory("901", 13L, newer)));
        mvc.perform(get("/api/history?page=0&size=2").session(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(entries.get(2).getId()))
                .andExpect(jsonPath("$[1].id").value(entries.get(0).getId()));
        mvc.perform(get("/api/history?page=1&size=2").session(viewer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].videoId").value(12));
        mvc.perform(get("/api/history?page=2&size=2").session(viewer)).andExpect(content().json("[]"));
    }

    @Test
    void invalidInputDoesNotCreateRecords() throws Exception {
        for (String json : List.of("{}", "{\"videoId\":0}", "{\"videoId\":-1}", "{\"videoId\":null}", "{broken")) {
            mvc.perform(post("/api/history").session(viewer).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest());
        }
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/history?" + query).session(viewer)).andExpect(status().isBadRequest());
        }
        mvc.perform(delete("/api/history/0").session(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/history").session(viewer)).andExpect(content().json("[]"));
    }

    @Test
    void repeatWatchesCreateSeparateHistoryEntries() throws Exception {
        add(viewer, 25L);
        add(viewer, 25L);
        mvc.perform(get("/api/history").session(viewer)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deletionChecksOwnerAndRemovesOnlyTheSelectedEntry() throws Exception {
        long id = add(viewer, 25L);
        add(viewer, 26L);
        mvc.perform(delete("/api/history/" + id + "?userId=901").session(other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/history/" + id).session(viewer)).andExpect(status().isNoContent());
        mvc.perform(get("/api/history").session(viewer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].videoId").value(26));
        mvc.perform(delete("/api/history/" + id).session(viewer)).andExpect(status().isNotFound());
    }

    @Test
    void clearingHistoryAffectsOnlyTheSessionUser() throws Exception {
        add(viewer, 25L);
        add(viewer, 26L);
        add(other, 27L);
        mvc.perform(delete("/api/history?userId=902").session(viewer)).andExpect(status().isNoContent());
        mvc.perform(get("/api/history").session(viewer)).andExpect(content().json("[]"));
        mvc.perform(get("/api/history").session(other)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/history").session(viewer)).andExpect(status().isNoContent());
    }

    private long add(MockHttpSession session, Long videoId) throws Exception {
        String body = mvc.perform(post("/api/history").session(session).contentType("application/json")
                .content("{\"videoId\":" + videoId + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asLong();
    }

    private MockHttpSession session(Long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        return session;
    }

    private record HistoryVideoFixture(VideoEntity video, Long ownerId) {}

    private HistoryVideoFixture video(VideoVisibility visibility, boolean thumbnail) {
        AccountEntity owner = new AccountEntity();
        owner.setFirstname("History");
        owner.setLastname("Test");
        owner.setEmail(UUID.randomUUID() + "@example.com");
        owner.setPasswordHash("unused-test-hash");
        entityManager.persist(owner);
        Long ownerId = (Long) entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(owner);
        ChannelEntity channel = new ChannelEntity();
        channel.setUser(owner);
        channel.setChannelName("History test channel");
        entityManager.persist(channel);
        VideoEntity video = new VideoEntity();
        video.setChannel(channel);
        video.setTitle("A real watched video");
        video.setVideo(new byte[]{1});
        video.setVideoContentType("video/mp4");
        video.setDuration(LocalTime.of(0, 1));
        video.setVisibility(visibility);
        if (visibility == VideoVisibility.MEMBERS_ONLY) video.setRequiredTierLevel(1);
        if (thumbnail) { video.setThumbnail(new byte[]{1}); video.setThumbnailContentType("image/png"); }
        entityManager.persist(video);
        entityManager.flush();
        return new HistoryVideoFixture(video, ownerId);
    }
}
