package ITubeBackend;

import ITubeBackend.entities.AccountEntity;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.LikedVideoEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.service.likedvideos.LikedVideoService;
import ITubeBackend.controller.LikedVideoController;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Transactional
class LikedVideoTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LikedVideoService service;

    @Autowired
    private LikedVideoController controller;

    private MockMvc mvc;
    private MockHttpSession session;

    private AccountEntity viewer;
    private AccountEntity owner;
    private Long viewerId;

    @BeforeEach
    void createAccounts() {
        viewer = account();
        owner = account();
        viewerId = (Long) entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil().getIdentifier(viewer);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        session = new MockHttpSession();
        session.setAttribute("userId", viewerId);
    }

    @Test
    void apiSupportsLikeStatusListAndIdempotentUnlike() throws Exception {
        VideoEntity video = video(owner, "API video", VideoVisibility.PUBLIC);
        entityManager.flush();
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
        VideoEntity video = video(owner, "Later private", VideoVisibility.PUBLIC);
        entityManager.flush();
        Long ownerId = (Long) entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(owner);
        service.likeVideo(viewerId, video.getVideoId());
        service.likeVideo(ownerId, video.getVideoId());
        video.setVisibility(VideoVisibility.PRIVATE);
        entityManager.flush();
        service.unlikeVideo(viewerId, video.getVideoId());
        assertTrue(service.getLikedVideos(viewerId, 0, 20).isEmpty());
        assertEquals(1, service.getLikedVideos(ownerId, 0, 20).size());
        assertTrue(service.getLikeStatus(ownerId, video.getVideoId()).liked());
    }

    @Test
    void reLikingAfterUnlikeCreatesAFreshTimestamp() {
        VideoEntity video = video(owner, "Like again", VideoVisibility.PUBLIC);
        like(viewer, video, Instant.parse("2000-01-01T00:00:00Z"));
        entityManager.flush();
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
        VideoEntity first = video(owner, "First", VideoVisibility.PUBLIC);
        VideoEntity old = video(owner, "Older", VideoVisibility.PUBLIC);
        VideoEntity last = video(owner, "Latest", VideoVisibility.PUBLIC);
        like(viewer, first, newer);
        like(viewer, old, older);
        like(viewer, last, newer);
        like(owner, video(owner, "Another user's like", VideoVisibility.PUBLIC), newer);
        entityManager.flush();
        entityManager.clear();

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
        like(viewer, video(owner, "Hidden", VideoVisibility.PRIVATE), time);
        VideoEntity members = video(owner, "Members", VideoVisibility.MEMBERS_ONLY);
        members.setRequiredTierLevel(1);
        like(viewer, members, time);
        like(viewer, video(viewer, "My private video", VideoVisibility.PRIVATE), time);
        entityManager.flush();
        entityManager.clear();

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
        VideoEntity video = video(owner, "A liked video", VideoVisibility.PUBLIC);
        entityManager.flush();

        service.likeVideo(viewerId, video.getVideoId());
        var first = service.getLikedVideos(viewerId, 0, 20);
        assertEquals(1, first.size());
        assertEquals(video.getVideoId(), first.get(0).video().videoId());
        service.likeVideo(viewerId, video.getVideoId());
        var repeated = service.getLikedVideos(viewerId, 0, 20);
        assertEquals(1, repeated.size());
        assertEquals(first.get(0).likedAt(), repeated.get(0).likedAt());

        Long ownerId = (Long) entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil().getIdentifier(owner);
        service.likeVideo(ownerId, video.getVideoId());
        assertEquals(1, service.getLikedVideos(ownerId, 0, 20).size());
        assertEquals(1, service.getLikedVideos(viewerId, 0, 20).size());
    }

    @Test
    void cannotLikeAnotherOwnersPrivateVideo() {
        VideoEntity video = video(owner, "Private video", VideoVisibility.PRIVATE);
        entityManager.flush();
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(viewerId, video.getVideoId()));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals(0L, entityManager.createQuery("select count(l) from LikedVideoEntity l", Long.class)
                .getSingleResult());
    }

    @Test
    void ownerCanLikeTheirPrivateVideo() {
        VideoEntity video = video(viewer, "My private video", VideoVisibility.PRIVATE);
        entityManager.flush();
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
    void missingAccountReturnsUnauthorized() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(Long.MAX_VALUE, 1L));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void nonPositiveVideoIdReturnsBadRequest() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.likeVideo(viewerId, 0L));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    private AccountEntity account() {
        AccountEntity account = new AccountEntity();
        account.setFirstname("Test");
        account.setLastname("User");
        account.setEmail(UUID.randomUUID() + "@example.com");
        account.setPasswordHash("unused-test-password-hash");
        entityManager.persist(account);
        return account;
    }

    private VideoEntity video(AccountEntity account, String title, VideoVisibility visibility) {
        ChannelEntity channel = new ChannelEntity();
        channel.setUser(account);
        channel.setChannelName("Test channel");
        entityManager.persist(channel);
        VideoEntity video = new VideoEntity();
        video.setChannel(channel);
        video.setTitle(title);
        video.setDuration(LocalTime.of(0, 1));
        video.setVideo(new byte[]{1});
        video.setVideoContentType("video/mp4");
        video.setVisibility(visibility);
        entityManager.persist(video);
        return video;
    }

    private void like(AccountEntity user, VideoEntity video, Instant time) {
        entityManager.persist(new LikedVideoEntity(user, video, time));
    }
}
