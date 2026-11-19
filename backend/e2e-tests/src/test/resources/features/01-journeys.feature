Feature: ITube end to end, through the API gateway
  Runs against the real, running stack (every service, gateway, databases, Kafka, object storage) exactly like the
  browser does: one origin, a session cookie, and the gateway routing each call to the right microservice.

  Scenario: A creator publishes a video and a viewer engages with it
    Given "Creator" registers a new account
    And "Viewer" registers a new account
    When "Creator" uploads a public video titled "E2E clip" with a resumable upload
    Then the video appears on the home feed within 20 seconds
    When "Viewer" watches the video
    And "Viewer" likes the video
    And "Viewer" comments "Nice one" on the video
    Then the history of "Viewer" shows the video with its title
    And "Viewer" sees the video among their liked videos
    And the comments on the video show "Nice one" by "Viewer"
    When "Viewer" subscribes to the channel of "Creator"
    Then "Viewer" sees the channel of "Creator" in their subscriptions
    When "Creator" makes the video private
    Then "Viewer" can no longer play the video

  Scenario: Signing in once works for every service
    Given "Roamer" registers a new account
    Then "Roamer" is signed in on identity, catalog, engagement and payments

  Scenario: Internal service APIs are not reachable from outside
    Then a request to "/internal/channels?ownerUserId=1" through the gateway is answered with 404
    And a request to "/actuator/health" through the gateway is answered with 404
