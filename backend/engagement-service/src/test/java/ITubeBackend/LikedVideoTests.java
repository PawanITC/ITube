package ITubeBackend;

import ITubeBackend.engagement.LikeStore;
import ITubeBackend.engagement.memory.InMemoryEngagementStores;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.service.likedvideos.LikedVideoService;
import ITubeBackend.controller.LikedVideoController;
import ITubeBackend.support.FakeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Import(FakeCatalog.Config.class)
class LikedVideoTests {
    private static final AtomicLong USERS = new AtomicLong(8000);

    // catalog-service is another process; videos come from this in-memory stand-in
    @Autowired
    private FakeCatalog catalog;

    @Autowired
    private LikedVideoService service;

    @Autowired
    private LikedVideoController controller;

    @Autowired
    private LikeStore likeStore;

    private MockMvc mvc;
    private MockHttpSession session;

    private Long viewerId;
    private Long ownerId;

    @BeforeEach
    void createAccounts() {
        viewerId = USERS.incrementAndGet();
        ownerId = USERS.incrementAndGet();
        catalog.reset();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        // Likes are in the in-memory engagement store (ScyllaDB in production), not the rolled-back database
        ((InMemoryEngagementStores.Resettable) likeStore).reset();
        session = new MockHttpSession();
        session.setAttribute("userId", viewerId);
    }

    @Test
    void apiSupportsLikeStatusListAndIdempotentUnlike() throws Exception {
        FakeCatalog.Video video = video(ownerId, "API video", VideoVisibility.PUBLIC);
        String path = "/api/liked-videos/" + video.getVideoId();
        mvc.perform(get(path).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(false));
        mvc.perform(put(path).session(session)).andExpect(status().isNoContent());
        mvc.perform(put(path).session(session)).andExpect(status().isNoContent());
        mvc.perform(get(path).session(session)).andExpect(jsonPath("$.liked").value(true));
        mvc.perform(get("/api/liked-videos").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].video.title").value("API video"));
        mvc.perform(delete(path).session(session)).andExpect(status().isNoContent());
        mvc.perform(delete(path).session(session)).andExpect(status().isNoContent());
        mvc.perform(get(path).session(session)).andExpect(jsonPath("$.liked").value(false));
    }

    @Test
    void apiRequiresSessionForEveryOperation() throws Exception {
        mvc.perform(get("/api/liked-videos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/liked-videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/liked-videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/liked-videos/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void apiRejectsInvalidIdsAndPageParameters() throws Exception {
        for (String query : java.util.List.of("page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/liked-videos?" + query).session(session)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/liked-videos/0").session(session)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/liked-videos/-1").session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/liked-videos/abc").session(session)).andExpect(status().isBadRequest());
    }

    @Test
    void unlikeAffectsOnlyTheSignedInUserAndWorksAfterVideoBecomesPrivate() {
        FakeCatalog.Video video = video(ownerId, "Later private", VideoVisibility.PUBLIC);
        service.likeVideo(viewerId, video.getVideoId());
        service.likeVideo(ownerId, video.getVideoId());
        video.setVisibility(VideoVisibility.PRIVATE);
        service.unlikeVideo(viewerId, video.getVideoId());
        assertTrue(service.getLikedVideos(viewerId, 0, 20).isEmpty());
        assertEquals(1, service.getLikedVideos(ownerId, 0, 20).size());
        assertTrue(service.getLikeStatus(ownerId, video.getVideoId()).liked());
    }

    @Test
    void reLikingAfterUnlikeCreatesAFreshTimestamp() {
        FakeCatalog.Video video = video(ownerId, "Like again", VideoVisibility.PUBLIC);
        like(viewerId, video, Instant.parse("2000-01-01T00:00:00Z"));
        service.unlikeVideo(viewerId, video.getVideoId());
        service.likeVideo(viewerId, video.getVideoId());
        assertTrue(service.getLikedVideos(viewerId, 0, 20).get(0).likedAt().isAfter(Instant.parse("2000-01-01T00:00:00Z")));
    }

    @Test
    void deleteOfMissingLikeOrMissingVideoIsSuccessful() {
        service.unlikeVideo(viewerId, Long.MAX_VALUE);
        assertTrue(service.getLikedVideos(viewerId, 0, 20).isEmpty());
    }

    @Test
    void returnsOnlyViewersLikesWithVideoDetailsNewestFirstAndStablePaging() {
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-01-02T10:00:00Z");
        FakeCatalog.Video first = video(ownerId, "First", VideoVisibility.PUBLIC);
        FakeCatalog.Video old = video(ownerId, "Older", VideoVisibility.PUBLIC);
        FakeCatalog.Video last = video(ownerId, "Latest", VideoVisibility.PUBLIC);
        like(viewerId, first, newer);
        like(viewerId, old, older);
        like(viewerId, last, newer);
        like(ownerId, video(ownerId, "Another user's like", VideoVisibility.PUBLIC), newer);

        var page = service.getLikedVideos(viewerId, 0, 2);
        assertEquals(2, page.size());
        assertEquals(last.getVideoId(), page.get(0).video().videoId());
        assertEquals(first.getVideoId(), page.get(1).video().videoId());
        assertEquals("Latest", page.get(0).video().title());
        assertEquals("Test channel", page.get(0).video().channelName());
        assertEquals(newer, page.get(0).likedAt());
        assertEquals("/api/videos/" + last.getVideoId() + "/file", page.get(0).video().videoUrl());
        assertNull(page.get(0).video().thumbnailUrl());
        var secondPage = service.getLikedVideos(viewerId, 1, 2);
        assertEquals(1, secondPage.size());
        assertEquals(old.getVideoId(), secondPage.get(0).video().videoId());
        assertTrue(service.getLikedVideos(viewerId, 2, 2).isEmpty());
    }

    @Test
    void hidesOtherOwnersPrivateVideosAndDoesNotExposeMembersOnlyStreamUrls() {
        Instant time = Instant.parse("2026-01-01T10:00:00Z");
        like(viewerId, video(ownerId, "Hidden", VideoVisibility.PRIVATE), time);
        FakeCatalog.Video members = video(ownerId, "Members", VideoVisibility.MEMBERS_ONLY);
        members.setRequiredTierLevel(1);
        like(viewerId, members, time);
        like(viewerId, video(viewerId, "My private video", VideoVisibility.PRIVATE), time);

        var likes = service.getLikedVideos(viewerId, 0, 20);
        assertEquals(2, likes.size());
        assertEquals("My private video", likes.get(0).video().title());
        assertEquals("Members", likes.get(1).video().title());
        assertNull(likes.get(0).video().videoUrl());
        assertNull(likes.get(1).video().videoUrl());
    }

    @Test
    void returnsEmptyListForUserWithoutLikes() {
        assertTrue(service.getLikedVideos(viewerId, 0, 20).isEmpty());
    }

    @Test
    void likingVideoTwiceCreatesOneEntryAndPreservesOriginalTime() {
        FakeCatalog.Video video = video(ownerId, "A liked video", VideoVisibility.PUBLIC);

        service.likeVideo(viewerId, video.getVideoId());
        var first = service.getLikedVideos(viewerId, 0, 20);
        assertEquals(1, first.size());
        assertEquals(video.getVideoId(), first.get(0).video().videoId());
        service.likeVideo(viewerId, video.getVideoId());
        var repeated = service.getLikedVideos(viewerId, 0, 20);
        assertEquals(1, repeated.size());
        assertEquals(first.get(0).likedAt(), repeated.get(0).likedAt());

        service.likeVideo(ownerId, video.getVideoId());
        assertEquals(1, service.getLikedVideos(ownerId, 0, 20).size());
        assertEquals(1, service.getLikedVideos(viewerId, 0, 20).size());
    }

    @Test
    void cannotLikeAnotherOwnersPrivateVideo() {
        FakeCatalog.Video video = video(ownerId, "Private video", VideoVisibility.PRIVATE);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(viewerId, video.getVideoId()));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertTrue(likeStore.newestFirst(viewerId).findAny().isEmpty());
    }

    @Test
    void ownerCanLikeTheirPrivateVideo() {
        FakeCatalog.Video video = video(viewerId, "My private video", VideoVisibility.PRIVATE);
        service.likeVideo(viewerId, video.getVideoId());
        assertEquals(1, service.getLikedVideos(viewerId, 0, 20).size());
    }

    @Test
    void missingVideoReturnsNotFound() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(viewerId, Long.MAX_VALUE));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void missingSessionUserReturnsUnauthorized() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(null, 1L));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void nonPositiveVideoIdReturnsBadRequest() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(viewerId, 0L));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private FakeCatalog.Video video(Long owner, String title, VideoVisibility visibility) {
        FakeCatalog.Video video = catalog.add(owner, title, visibility);
        // Members-only tier is set explicitly by the test that needs it
        video.setRequiredTierLevel(null);
        return video;
    }

    private void like(Long user, FakeCatalog.Video video, Instant time) {
        likeStore.likeIfAbsent(user, video.getVideoId(), time);
    }
}
