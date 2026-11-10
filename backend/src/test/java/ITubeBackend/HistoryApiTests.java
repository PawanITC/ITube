package ITubeBackend;

import ITubeBackend.controller.HistoryController;
import ITubeBackend.entities.WatchHistory;
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
}
