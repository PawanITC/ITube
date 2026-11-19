package ITubeBackend.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.channel.ChannelDetailsDto;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.channel.ChannelSearchDto;
import ITubeBackend.dto.subscribtions.SubscriptionStatusDto;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.channel.ChannelService;
import ITubeBackend.service.channel.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Channels", description = "Channel pages, search and free Subscribe (never involves payment)")
@RestController
@RequestMapping("/api/channels")
public class ChannelController {
    private final ChannelService channelService;
    private final SubscriptionService subscriptionService;

    public ChannelController(ChannelService channelService, SubscriptionService subscriptionService) {
        this.channelService = channelService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/search")
    public List<ChannelSearchDto> searchChannels(@RequestParam(defaultValue = "") String q) {
        String query = q.trim();
        if (query.isEmpty()) return List.of();
        return channelService.search(query, CurrentUser.find().orElse(null));
    }

    @GetMapping("/subscriptions")
    public List<ChannelDto> getSubscribedChannels() {
        return channelService.subscribedChannels(CurrentUser.require("Sign in before subscribing"));
    }

    /** The signed-in user's channels (moved here from the old dev-session controller: channels are catalog data). */
    @GetMapping("/mine")
    public List<ChannelDto> getMyChannels() {
        return channelService.channelsOwnedBy(CurrentUser.require());
    }

    @GetMapping("/{channelId}")
    public ChannelDetailsDto getChannel(@PathVariable Long channelId) {
        return channelService.getChannelDetails(channelId, CurrentUser.find().orElse(null));
    }

    @PostMapping("/{channelId}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionStatusDto subscribe(@PathVariable Long channelId) {
        return subscriptionService.subscribe(CurrentUser.require("Sign in before subscribing"), channelId);
    }

    @DeleteMapping("/{channelId}/subscriptions")
    public SubscriptionStatusDto unsubscribe(@PathVariable Long channelId) {
        return subscriptionService.unsubscribe(CurrentUser.require("Sign in before subscribing"), channelId);
    }
}
