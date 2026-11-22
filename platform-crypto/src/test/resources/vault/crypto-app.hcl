# platform-crypto: what the app may do at runtime. Customer keys are named customer-<customer hex> and
# customer-<customer hex>-fp. The app never creates, rotates, exports or deletes keys.
path "transit/datakey/plaintext/customer-*" {
  capabilities = ["update"]
}
path "transit/decrypt/customer-*" {
  capabilities = ["update"]
}
path "transit/rewrap/customer-*" {
  capabilities = ["update"]
}
path "transit/hmac/customer-*" {
  capabilities = ["update"]
}
path "transit/keys/customer-*" {
  capabilities = ["read"]
}
path "secret/data/platform/*" {
  capabilities = ["read"]
}
