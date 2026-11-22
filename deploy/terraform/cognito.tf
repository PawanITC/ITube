# Amazon Cognito user pool for "Continue with Cognito" on the ITube sign-in page (docs/AUTH.md).
#
# identity-service runs the OAuth 2.0 authorization code flow with PKCE against the pool's hosted login page, then
# signs the user in with ITube's own tokens. The app client is public (no secret): PKCE protects the code exchange.
# Google, Apple, Facebook or a SAML/OIDC provider can be added to this pool later (aws_cognito_identity_provider +
# supported_identity_providers below) without changing ITube.

locals {
  cognito_domain_prefix = coalesce(var.cognito_domain_prefix, "itube-${data.aws_caller_identity.current.account_id}")
}

resource "aws_cognito_user_pool" "itube" {
  name = "itube-${var.cluster_name}"

  # Users sign in with their email address, which Cognito verifies (ITube only trusts verified emails)
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  username_configuration {
    case_sensitive = false
  }

  password_policy {
    minimum_length                   = 8
    require_lowercase                = false
    require_uppercase                = false
    require_numbers                  = false
    require_symbols                  = false
    temporary_password_validity_days = 7
  }

  mfa_configuration = "OPTIONAL"
  software_token_mfa_configuration {
    enabled = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  # Dev pool: Cognito's own email sender (limited to a few emails a day). Use SES for real traffic.
  email_configuration {
    email_sending_account = "COGNITO_DEFAULT"
  }

  deletion_protection = var.cognito_deletion_protection ? "ACTIVE" : "INACTIVE"
}

resource "aws_cognito_user_pool_domain" "itube" {
  domain       = local.cognito_domain_prefix
  user_pool_id = aws_cognito_user_pool.itube.id
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "itube-web"
  user_pool_id = aws_cognito_user_pool.itube.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = var.cognito_callback_urls
  logout_urls                          = var.cognito_logout_urls

  explicit_auth_flows           = ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true

  # ITube only reads the ID token once, during sign-in, and then issues its own tokens
  id_token_validity      = 5
  access_token_validity  = 5
  refresh_token_validity = 60
  token_validity_units {
    id_token      = "minutes"
    access_token  = "minutes"
    refresh_token = "minutes"
  }
}

# identity-service reads these through envFrom (deploy/k8s/identity.yaml, optional). Terraform owns this ConfigMap;
# the deploy workflow never touches it (kubectl apply without --prune).
resource "kubernetes_config_map_v1" "cognito" {
  metadata {
    name      = "itube-cognito"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  data = {
    COGNITO_ENABLED      = "true"
    COGNITO_REGION       = var.region
    COGNITO_USER_POOL_ID = aws_cognito_user_pool.itube.id
    COGNITO_CLIENT_ID    = aws_cognito_user_pool_client.web.id
    COGNITO_DOMAIN       = "https://${aws_cognito_user_pool_domain.itube.domain}.auth.${var.region}.amazoncognito.com"
  }
}
