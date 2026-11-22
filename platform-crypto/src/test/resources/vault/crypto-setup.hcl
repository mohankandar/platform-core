# platform-crypto: the set-up step. Creates the customer's keys and rotates the wrapping key; at customer exit,
# destroys them (crypto destroy-customer-keys: keys/<name>/config deletion_allowed, then DELETE keys/<name>).
path "transit/keys/customer-*" {
  capabilities = ["create", "update", "read", "delete"]
}
