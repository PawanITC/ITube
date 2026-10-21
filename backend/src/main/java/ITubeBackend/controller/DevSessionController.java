package ITubeBackend.controller;

import ITubeBackend.dto.accounts.DevSessionDto;
import ITubeBackend.dto.ChannelDto;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.service.account.AccountService;
import ITubeBackend.service.video.VideoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class DevSessionController {
    private final ChannelRepository channelRepository;
    private final AccountService accountService;

    private final VideoService videoService;

    public DevSessionController(ChannelRepository channelRepository, AccountService accountService,
                                VideoService videoService) {
        this.channelRepository = channelRepository;
        this.accountService = accountService;
        this.videoService = videoService;
    }

    public Long getCurrentSession(HttpSession session) {
        Object sessionUserId = session.getAttribute("userId");
        if (!(sessionUserId instanceof Long userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active user session");
        }
        return userId;
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE
    @PostMapping("/api/session/dev")
    public DevSessionDto createDevSession(HttpSession session) {
        DevSessionDto devSession = accountService.createDevSession();
        session.setAttribute("userId", devSession.userId());
        session.setAttribute("channelId", devSession.channelId());
        return devSession;
    }

    // REMOVE LATER - UNTIL LOGIN IS COMPLETE
    @PostMapping("/api/session/dev/random")
    public DevSessionDto createRandomDevSession(HttpSession session) {
        DevSessionDto devSession = accountService.createRandomDevSession();
        session.setAttribute("userId", devSession.userId());
        session.setAttribute("channelId", devSession.channelId());
        return devSession;
    }

    @GetMapping("/api/session/current")
    public Map<String, Long> getCurrentSessionDetails(HttpSession session) {
        Long userId = getCurrentSession(session);
        Object sessionChannelId = session.getAttribute("channelId");
        if (!(sessionChannelId instanceof Long channelId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No active channel session");
        }
        return Map.of("userId", userId, "channelId", channelId);
    }

    @GetMapping("/api/channels/mine")
    public List<ChannelDto> getMyChannels(HttpSession session) {
        return channelRepository.findDtosByUserId(getCurrentSession(session));
    }
}
