Feature: Watch history, likes and comments
  engagement-service keeps what viewers do (ScyllaDB in production, memory in tests). Video details come from
  catalog-service, and the signed-in user and their name come from the access token. A viewer's own data must stay
  available even when the catalog isn't.

  Background:
    Given a public video "Rust in production" by creator 77
    And a private video "Draft cut" by creator 77
    And a signed-in viewer "Sam"

  Scenario: Watching a video records it in history, newest first
    When "Sam" watches "Rust in production"
    And "Sam" watches "Draft cut"
    Then the history of "Sam" lists "Draft cut" then "Rust in production"
    And the "Draft cut" entry shows no video details because the video is private

  Scenario: History keeps working while the catalog is down
    Given "Sam" has watched "Rust in production"
    And catalog-service is down
    When "Sam" watches "Rust in production"
    Then the history of "Sam" still has 2 entries, without video details

  Scenario: Liking twice keeps one like
    When "Sam" likes "Rust in production"
    And "Sam" likes "Rust in production"
    Then "Sam" has 1 liked video

  Scenario: Someone else's private video can't be liked
    When "Sam" likes "Draft cut"
    Then the request is answered with status 404

  Scenario: Unliking still works while the catalog is down
    Given "Sam" has liked "Rust in production"
    And catalog-service is down
    When "Sam" unlikes "Rust in production"
    Then the request is answered with status 202

  Scenario: Comments are signed with the name from the access token
    When "Sam" comments "Great talk" on "Rust in production"
    Then the comments on "Rust in production" show "Great talk" by "Sam"
    And identity-service was not asked who "Sam" is
