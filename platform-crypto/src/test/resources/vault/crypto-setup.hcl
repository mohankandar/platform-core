# platform-crypto: the set-up step. Creates the customer's keys and rotates the wrapping key.
path "transit/keys/customer-*" {
  capabilities = ["create", "update", "read"]
}
