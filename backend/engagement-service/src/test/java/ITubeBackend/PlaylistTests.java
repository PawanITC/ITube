package ITubeBackend;

import ITubeBackend.controller.PlaylistController;
import ITubeBackend.engagement.PlaylistStore;
import ITubeBackend.engagement.PlaylistStore.Item;
import ITubeBackend.engagement.PlaylistStore.Playlist;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.memory.InMemoryPlaylistStore;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.service.playlists.PlaylistService;
import ITubeBackend.support.FakeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ITubeBackend.security.AccessTokens;
import ITubeBackend.security.AuthenticationConfig;
import ITubeBackend.security.SignedInUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@Import(FakeCatalog.Config.class)
class PlaylistTests {
    @Autowired PlaylistController controller;
    @Autowired PlaylistService service;
    @Autowired PlaylistStore store;
    @Autowired FakeCatalog catalog;
    MockMvc mvc;
    RequestPostProcessor viewer;
    @Autowired AccessTokens tokens;

    @BeforeEach
    void setup() {
        ((InMemoryPlaylistStore) store).reset();
        catalog.reset();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AuthenticationConfig.AuthenticationFilter(tokens)).build();
        viewer = session(901L);
    }

    @Test
    void createListRenameAndDeleteUseTheSessionOwner() throws Exception {
        mvc.perform(post("/api/playlists?userId=902").with(viewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  Learning Java  \"}"))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.operationId").isString());
        var playlist = service.list(901L, 0, 20).getFirst();
        assertThat(playlist.title()).isEqualTo("Learning Java");
        String path = "/api/playlists/" + playlist.id();
        assertThat(service.list(902L, 0, 20)).isEmpty();
        mvc.perform(get(path).with(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.videos.length()").value(0));
        mvc.perform(patch(path).with(viewer).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Spring Boot\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(delete(path).with(viewer)).andExpect(status().isAccepted());
        mvc.perform(get(path).with(viewer)).andExpect(status().isNotFound());
    }

    @Test
    void everyEndpointRequiresASession() throws Exception {
        String path = "/api/playlists/" + TimeUuids.at(Instant.now());
        mvc.perform(get("/api/playlists")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/playlists").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        mvc.perform(put(path + "/videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path + "/videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(put(path + "/videos/1/position").contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":0,\"version\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherUsersCannotReadOrMutateAPlaylist() throws Exception {
        var playlist = service.create(902L, "Private list");
        String path = "/api/playlists/" + playlist.id();
        catalog.setDown(true); // Ownership must be checked without asking another service.
        mvc.perform(get(path).with(viewer)).andExpect(status().isNotFound());
        mvc.perform(patch(path).with(viewer).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Mine\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).with(viewer)).andExpect(status().isNotFound());
        mvc.perform(put(path + "/videos/1").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(delete(path + "/videos/1").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(put(path + "/videos/1/position").with(viewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":0,\"version\":\"" + playlist.version() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesTitlesIdsPagingAndMoveRequests() throws Exception {
        for (String title : List.of("", "   ", "x".repeat(101))) {
            mvc.perform(post("/api/playlists").with(viewer).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + title + "\"}")).andExpect(status().isBadRequest());
        }
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc")) {
            mvc.perform(get("/api/playlists?" + query).with(viewer)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/playlists/not-a-uuid").with(viewer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/playlists/" + UUID.randomUUID()).with(viewer)).andExpect(status().isBadRequest());
        var playlist = service.create(901L, "Validation");
        String path = "/api/playlists/" + playlist.id();
        mvc.perform(put(path + "/videos/0").with(viewer)).andExpect(status().isBadRequest());
        for (String body : List.of("{}", "{\"position\":0}", "{\"version\":\"" + playlist.version() + "\"}")) {
            mvc.perform(put(path + "/videos/1/position").with(viewer).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        assertThat(service.list(901L, Integer.MAX_VALUE, 100)).isEmpty();
        assertThatThrownBy(() -> service.create(null, "No user")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void addsAreUniqueAndRemovalsAreIdempotentWithoutDeletingTheVideo() throws Exception {
        var playlist = service.create(901L, "Learning");
        var video = catalog.add(902L, "Spring", VideoVisibility.PUBLIC).setThumbnail(true);
        String path = "/api/playlists/" + playlist.id() + "/videos/" + video.getVideoId();
        mvc.perform(put(path).with(viewer)).andExpect(status().isAccepted());
        var before = service.detail(901L, playlist.id(), 0, 20);
        mvc.perform(put(path).with(viewer)).andExpect(status().isAccepted());
        var after = service.detail(901L, playlist.id(), 0, 20);
        assertThat(after.playlist().version()).isEqualTo(before.playlist().version());
        assertThat(after.videos().getFirst().addedAt()).isEqualTo(before.videos().getFirst().addedAt());
        assertThat(after.videos().getFirst().video().thumbnailUrl()).isEqualTo("/api/videos/" + video.getVideoId() + "/thumbnail");
        mvc.perform(delete(path).with(viewer)).andExpect(status().isAccepted());
        mvc.perform(delete(path).with(viewer)).andExpect(status().isAccepted());
        assertThat(catalog.videoAccess(video.getVideoId())).isPresent();
    }

    @Test
    void movingVideoPersistsOrderAndRejectsStaleEdits() throws Exception {
        var playlist = service.create(901L, "Order");
        var first = catalog.add(902L, "First", VideoVisibility.PUBLIC);
        var second = catalog.add(902L, "Second", VideoVisibility.PUBLIC);
        var third = catalog.add(902L, "Third", VideoVisibility.PUBLIC);
        for (var video : List.of(first, second, third)) service.addVideo(901L, playlist.id(), video.getVideoId());
        var before = service.detail(901L, playlist.id(), 0, 2);
        String path = "/api/playlists/" + playlist.id() + "/videos/" + third.getVideoId() + "/position";
        String body = "{\"position\":0,\"version\":\"" + before.playlist().version() + "\"}";
        mvc.perform(put(path).with(viewer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        mvc.perform(put(path).with(viewer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        var page = service.detail(901L, playlist.id(), 0, 2);
        assertThat(page.videos()).extracting(row -> row.videoId()).containsExactly(third.getVideoId(), first.getVideoId());
        assertThat(page.hasMore()).isTrue();
        var last = service.detail(901L, playlist.id(), 1, 2);
        assertThat(last.videos().getFirst().videoId()).isEqualTo(second.getVideoId());
        assertThat(last.videos().getFirst().position()).isEqualTo(2);
        assertThat(last.hasMore()).isFalse();
        assertThat(service.detail(901L, playlist.id(), Integer.MAX_VALUE, 100).videos()).isEmpty();
    }

    @Test
    void privateAndDeletedVideosRemainRemovableWithoutLeakingMetadata() {
        var playlist = service.create(901L, "Keep my list");
        var video = catalog.add(902L, "Later private", VideoVisibility.PUBLIC);
        service.addVideo(901L, playlist.id(), video.getVideoId());
        video.setVisibility(VideoVisibility.PRIVATE);
        assertThat(service.detail(901L, playlist.id(), 0, 20).videos().getFirst().video()).isNull();
        catalog.delete(video.getVideoId());
        var current = service.detail(901L, playlist.id(), 0, 20);
        service.moveVideo(901L, playlist.id(), video.getVideoId(), 0, current.playlist().version());
        service.removeVideo(901L, playlist.id(), video.getVideoId());
        assertThat(service.detail(901L, playlist.id(), 0, 20).videos()).isEmpty();
    }

    @Test
    void addingRespectsPrivateUnlistedAndMembersOnlyVisibility() throws Exception {
        var playlist = service.create(901L, "Visibility");
        var privateVideo = catalog.add(902L, "Hidden", VideoVisibility.PRIVATE);
        mvc.perform(put("/api/playlists/" + playlist.id() + "/videos/" + privateVideo.getVideoId()).with(viewer))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/playlists/" + playlist.id() + "/videos/999999").with(viewer)).andExpect(status().isNotFound());
        var members = catalog.add(902L, "Members", VideoVisibility.MEMBERS_ONLY);
        var unlisted = catalog.add(902L, "Unlisted", VideoVisibility.UNLISTED);
        service.addVideo(901L, playlist.id(), members.getVideoId());
        service.addVideo(901L, playlist.id(), unlisted.getVideoId());
        var videos = service.detail(901L, playlist.id(), 0, 20).videos();
        assertThat(videos.getFirst().video().videoUrl()).isNull();
        assertThat(videos.getLast().video().videoUrl()).isEqualTo("/api/videos/" + unlisted.getVideoId() + "/file");
        var owned = service.create(902L, "Owner's private videos");
        service.addVideo(902L, owned.id(), privateVideo.getVideoId());
        assertThat(service.detail(902L, owned.id(), 0, 20).videos().getFirst().video()).isNotNull();
    }

    @Test
    void catalogOutageDoesNotBlockLibraryEditingOrDeletion() {
        var playlist = service.create(901L, "Offline catalog");
        var video = catalog.add(902L, "Saved", VideoVisibility.PUBLIC);
        service.addVideo(901L, playlist.id(), video.getVideoId());
        catalog.setDown(true);
        assertThat(service.list(901L, 0, 20)).hasSize(1);
        assertThat(service.detail(901L, playlist.id(), 0, 20).videos().getFirst().video()).isNull();
        service.rename(901L, playlist.id(), "Renamed offline");
        service.removeVideo(901L, playlist.id(), video.getVideoId());
        service.delete(901L, playlist.id());
        assertThat(service.list(901L, 0, 20)).isEmpty();
    }

    @Test
    void playlistLimitKeepsRowsBoundedAndDuplicateAddStillSucceedsAtTheLimit() {
        Instant now = Instant.now();
        var video = catalog.add(902L, "Already there", VideoVisibility.PUBLIC);
        var items = LongStream.range(1, 500).mapToObj(id -> new Item(id, now)).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        items.add(new Item(video.getVideoId(), now));
        var playlist = new Playlist(TimeUuids.at(now), 901L, "Full", now, now, UUID.randomUUID(), items);
        store.create(playlist);
        assertThat(service.addVideo(901L, playlist.id(), video.getVideoId()).videoCount()).isEqualTo(500);
        var extra = catalog.add(902L, "Extra", VideoVisibility.PUBLIC);
        assertThatThrownBy(() -> service.addVideo(901L, playlist.id(), extra.getVideoId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void listsOnlyOwnedPlaylistsNewestFirstWithPaging() {
        var first = service.create(901L, "First");
        var second = service.create(901L, "Second");
        service.create(902L, "Other user");
        assertThat(service.list(901L, 0, 1)).extracting(value -> value.id()).containsExactly(second.id());
        assertThat(service.list(901L, 1, 1)).extracting(value -> value.id()).containsExactly(first.id());
    }

    private RequestPostProcessor session(long userId) {
        String token = tokens.issue(new SignedInUser(userId, null, null));
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
