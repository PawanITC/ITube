package ITubeBackend;

import ITubeBackend.controller.SavedVideoController;
import ITubeBackend.engagement.SavedVideoStore;
import ITubeBackend.engagement.memory.InMemorySavedVideoStore;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.service.savedvideos.SavedVideoService;
import ITubeBackend.support.FakeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.AuthenticationConfig;
import ITubeBackend.security.SignedInUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Import(FakeCatalog.Config.class)
class SavedVideoTests {
    @Autowired SavedVideoController controller;
    @Autowired SavedVideoService service;
    @Autowired SavedVideoStore store;
    @Autowired FakeCatalog catalog;
    MockMvc mvc;
    RequestPostProcessor viewer;
    @Autowired AccessTokens tokens;

    @BeforeEach
    void setup() {
        ((InMemorySavedVideoStore) store).reset();
        catalog.reset();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AuthenticationConfig.AuthenticationFilter(tokens)).build();
        viewer = session(901L);
    }

    @Test
    void saveStatusListAndRemovalUseTheSessionAndAreIdempotent() throws Exception {
        var video = catalog.add(902L, "Saved video", VideoVisibility.PUBLIC).setThumbnail(true);
        String path = "/api/saved-videos/" + video.getVideoId();
        mvc.perform(get(path).with(viewer)).andExpect(jsonPath("$.saved").value(false));
        mvc.perform(put(path + "?userId=902").with(viewer)).andExpect(status().isAccepted());
        Instant first = service.list(901L, 0, 20).getFirst().savedAt();
        mvc.perform(put(path).with(viewer)).andExpect(status().isAccepted());
        assertThat(service.list(901L, 0, 20)).hasSize(1);
        assertThat(service.list(901L, 0, 20).getFirst().savedAt()).isEqualTo(first);
        mvc.perform(get(path).with(viewer)).andExpect(jsonPath("$.saved").value(true));
        mvc.perform(get("/api/saved-videos").with(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].videoId").value(video.getVideoId()))
                .andExpect(jsonPath("$[0].video.title").value("Saved video"))
                .andExpect(jsonPath("$[0].video.thumbnailUrl").value("/api/videos/" + video.getVideoId() + "/thumbnail"));
        assertThat(service.list(902L, 0, 20)).isEmpty();
        mvc.perform(delete(path).with(viewer)).andExpect(status().isAccepted());
        mvc.perform(delete(path).with(viewer)).andExpect(status().isAccepted());
        mvc.perform(get(path).with(viewer)).andExpect(jsonPath("$.saved").value(false));
    }

    @Test
    void allEndpointsRequireASignedInUser() throws Exception {
        mvc.perform(get("/api/saved-videos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/saved-videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/saved-videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/saved-videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/saved-videos")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidIdsAndPagingAndHandlesLargePageWithoutIntegerOverflow() throws Exception {
        for (String query : java.util.List.of("page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/saved-videos?" + query).with(viewer)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/saved-videos/0").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/saved-videos/-1").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/saved-videos/abc").with(viewer)).andExpect(status().isBadRequest());
        assertThat(service.list(901L, Integer.MAX_VALUE, 100)).isEmpty();
        assertThatThrownBy(() -> service.save(null, 1L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void newestFirstPaginationIsStableAndUserSpecific() {
        var first = catalog.add(902L, "First", VideoVisibility.PUBLIC);
        var second = catalog.add(902L, "Second", VideoVisibility.PUBLIC);
        var third = catalog.add(902L, "Third", VideoVisibility.PUBLIC);
        Instant time = Instant.parse("2026-11-22T10:00:00Z");
        store.saveIfAbsent(901L, first.getVideoId(), time.minusSeconds(60));
        store.saveIfAbsent(901L, second.getVideoId(), time);
        store.saveIfAbsent(901L, third.getVideoId(), time);
        store.saveIfAbsent(902L, first.getVideoId(), time);
        assertThat(service.list(901L, 0, 2)).extracting(row -> row.videoId())
                .containsExactly(third.getVideoId(), second.getVideoId());
        assertThat(service.list(901L, 1, 2)).extracting(row -> row.videoId()).containsExactly(first.getVideoId());
        assertThat(service.list(901L, 2, 2)).isEmpty();
        assertThat(service.list(902L, 0, 20)).hasSize(1);
    }

    @Test
    void missingAndOtherOwnersPrivateVideosCannotBeSaved() throws Exception {
        var video = catalog.add(902L, "Private", VideoVisibility.PRIVATE);
        mvc.perform(put("/api/saved-videos/" + video.getVideoId()).with(viewer)).andExpect(status().isNotFound());
        mvc.perform(put("/api/saved-videos/999999").with(viewer)).andExpect(status().isNotFound());
        service.save(902L, video.getVideoId());
        assertThat(service.list(902L, 0, 20).getFirst().video().title()).isEqualTo("Private");
    }

    @Test
    void unavailableVideosKeepRemovableRowsWithoutLeakingMetadata() {
        var video = catalog.add(902L, "Later private", VideoVisibility.PUBLIC);
        service.save(901L, video.getVideoId());
        video.setVisibility(VideoVisibility.PRIVATE);
        assertThat(service.list(901L, 0, 20).getFirst().video()).isNull();
        catalog.delete(video.getVideoId());
        assertThat(service.list(901L, 0, 20).getFirst().videoId()).isEqualTo(video.getVideoId());
        service.remove(901L, video.getVideoId());
        assertThat(service.list(901L, 0, 20)).isEmpty();
    }

    @Test
    void unlistedAndMembersOnlyVideosFollowExistingCatalogPlaybackRules() {
        var unlisted = catalog.add(902L, "Unlisted", VideoVisibility.UNLISTED);
        var members = catalog.add(902L, "Members", VideoVisibility.MEMBERS_ONLY);
        service.save(901L, unlisted.getVideoId());
        service.save(901L, members.getVideoId());
        var videos = service.list(901L, 0, 20);
        assertThat(videos).hasSize(2);
        assertThat(videos.stream().filter(row -> row.videoId() == members.getVideoId()).findFirst().orElseThrow()
                .video().videoUrl()).isNull();
        assertThat(videos.stream().filter(row -> row.videoId() == unlisted.getVideoId()).findFirst().orElseThrow()
                .video().videoUrl()).isEqualTo("/api/videos/" + unlisted.getVideoId() + "/file");
    }

    @Test
    void catalogOutageDoesNotBlockListStatusRemovalOrClear() throws Exception {
        var video = catalog.add(902L, "Saved", VideoVisibility.PUBLIC);
        service.save(901L, video.getVideoId());
        service.save(902L, video.getVideoId());
        catalog.setDown(true);
        assertThat(service.list(901L, 0, 20).getFirst().video()).isNull();
        assertThat(service.status(901L, video.getVideoId()).saved()).isTrue();
        service.remove(901L, video.getVideoId());
        assertThat(service.list(901L, 0, 20)).isEmpty();
        mvc.perform(delete("/api/saved-videos").with(viewer)).andExpect(status().isAccepted());
        assertThat(service.status(902L, video.getVideoId()).saved()).isTrue();
        service.clear(902L);
        assertThat(service.list(902L, 0, 20)).isEmpty();
    }

    @Test
    void clearOnlyRemovesTheSignedInUsersSavedVideos() throws Exception {
        var video = catalog.add(902L, "Saved by both", VideoVisibility.PUBLIC);
        service.save(901L, video.getVideoId());
        service.save(902L, video.getVideoId());
        mvc.perform(delete("/api/saved-videos?userId=902").with(viewer)).andExpect(status().isAccepted());
        assertThat(service.list(901L, 0, 20)).isEmpty();
        assertThat(service.list(902L, 0, 20)).hasSize(1);
    }

    private RequestPostProcessor session(long userId) {
        String token = tokens.issue(new SignedInUser(userId, null, null));
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
