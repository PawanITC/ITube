mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

override_data {
  target = data.aws_caller_identity.current
  values = { account_id = "160198385987" }
}
override_data {
  target = data.aws_s3_bucket.media
  values = {
    id                          = "itube-media-160198385987"
    arn                         = "arn:aws:s3:::itube-media-160198385987"
    bucket_regional_domain_name = "itube-media-160198385987.s3.eu-west-2.amazonaws.com"
  }
}

variables {
  signing_public_key_pem = "-----BEGIN PUBLIC KEY-----\nMOCK_ONLY\n-----END PUBLIC KEY-----"
}

run "private_media_and_public_assets_are_separate" {
  # Apply against the mock provider only: no AWS resources or charges.
  command = apply
  assert {
    condition     = !aws_cloudfront_distribution.cdn.enabled
    error_message = "The demo must remain disabled unless explicitly enabled for a session."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.cdn.origin) == 2
    error_message = "The demo must have two distinct origins."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.cdn.default_cache_behavior[0].trusted_key_groups) == 1
    error_message = "Default/media requests must require viewer signatures."
  }
  assert {
    condition     = aws_cloudfront_distribution.cdn.ordered_cache_behavior[0].path_pattern == "/assets/*" && aws_cloudfront_distribution.cdn.ordered_cache_behavior[0].trusted_key_groups == null
    error_message = "Only the assets path may be public."
  }
  assert {
    condition     = aws_cloudfront_origin_access_control.s3.signing_behavior == "always" && aws_s3_bucket_public_access_block.assets.block_public_policy
    error_message = "Keep S3 private and sign all origin requests."
  }
  assert {
    condition     = aws_cloudfront_cache_policy.objects["media"].min_ttl == 0 && aws_cloudfront_cache_policy.objects["media"].parameters_in_cache_key_and_forwarded_to_origin[0].query_strings_config[0].query_string_behavior == "none"
    error_message = "Do not override no-store or fragment the media cache by viewer signature."
  }
  assert {
    condition     = aws_cloudfront_distribution.cdn.price_class == "PriceClass_All" && !aws_s3_bucket.assets.force_destroy
    error_message = "Use global edge coverage and preserve assets on accidental destroy."
  }
  assert {
    condition     = local.origin_buckets.media.prefix == "videos/*" && local.origin_buckets.assets.prefix == "assets/*"
    error_message = "Each origin must expose only its own key prefix."
  }
}
