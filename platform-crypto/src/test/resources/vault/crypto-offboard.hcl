# platform-crypto: customer exit only (crypto destroy-customer-keys). The only policy that destroys key material
# (P044, SEC-2): reads a key, allows its deletion (transit/keys/<name>/config deletion_allowed) and deletes it. Given
# alone to the offboarding run, never to the set-up step or the app.
path "transit/keys/+" {
  capabilities = ["read", "delete"]
}
path "transit/keys/+/config" {
  capabilities = ["update"]
}
