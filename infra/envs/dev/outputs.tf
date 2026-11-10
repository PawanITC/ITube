output "aws_account_id" {
  description = "Account Terraform is running against (sanity check for the first run)."
  value       = data.aws_caller_identity.current.account_id
}
