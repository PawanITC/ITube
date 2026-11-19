Feature: Accounts and signing in
  identity-service owns accounts. Signing in stores the user in a session shared (through Redis) by every ITube
  service. Channels belong to catalog-service, so registration asks the catalog for the user's first channel - and
  must still work when the catalog is down.

  Scenario: Registering signs the user in everywhere and creates their channel
    When "Ada" registers with the channel name "Ada's Lab"
    Then the registration succeeds
    And the session holds the user's id, channel and display name "Ada Lovelace"
    And the catalog was asked to create the channel "Ada's Lab"

  Scenario: An email address can only be registered once, whatever its capitals
    Given "Grace" has registered
    When someone registers again with the same email in capitals
    Then that registration is refused as a conflict

  Scenario: A wrong password is refused
    Given "Linus" has registered
    When "Linus" signs in with a wrong password
    Then signing in is refused

  Scenario: Registration still works while the catalog is down
    Given catalog-service is down
    When "Barbara" registers with the channel name "Barbara's Lab"
    Then the registration succeeds without a channel for now
    And an account-created event asks the catalog to create "Barbara's Lab" later

  Scenario: Signing in still works while the catalog is down
    Given "Margaret" has registered
    And catalog-service is down
    When "Margaret" signs in with the right password
    Then signing in succeeds without channel details
