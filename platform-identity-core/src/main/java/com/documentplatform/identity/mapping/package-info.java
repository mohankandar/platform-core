/**
 * The role mapping and client-binding files: for the setup command ({@code platform-setup}), not for apps.
 *
 * <p>{@link com.documentplatform.identity.mapping.RoleMappingFile}, {@link com.documentplatform.identity.mapping.MappingPlan},
 * {@link com.documentplatform.identity.mapping.MappingRow}, {@link com.documentplatform.identity.mapping.MappingError},
 * {@link com.documentplatform.identity.mapping.SlotTargets} and
 * {@link com.documentplatform.identity.mapping.ClientBindingsFile} are public only because the setup command, another
 * platform module, reads its input files with them. Apps load grants through the setup command (or the identity
 * stores), never by calling these parsers. {@link com.documentplatform.identity.mapping.Grant} is the grant value
 * shared by the stores, the testkit and setup.
 */
package com.documentplatform.identity.mapping;
