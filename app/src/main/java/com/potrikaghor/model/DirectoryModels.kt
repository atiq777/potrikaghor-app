package com.potrikaghor.model

/**
 * Canonical publisher identity.
 * Example: publisherId = "pub_prothom_alo", canonicalNameBn = "প্রথম আলো", countryCode = "BD"
 */
data class PublisherEntity(
    val publisherId: String,
    val canonicalNameBn: String,
    val canonicalNameEn: String,
    val countryCode: String = "BD", // BD, IN, UK, US, AU etc.
    val primaryRootDomain: String,
    val logoAssetKey: String? = null
)

/**
 * Specific edition or language variant of a publisher.
 * Example: editionId = "ed_prothom_alo_bn_bd", publisherId = "pub_prothom_alo", languageCode = "bn"
 */
data class EditionEntity(
    val editionId: String,
    val publisherId: String,
    val languageCode: String = "bn",
    val editionName: String = ""
)

/**
 * User-facing website or source listing for the Directory screen.
 * Example: directoryId = "dir_prothom_alo", editionId = "ed_prothom_alo_bn_bd"
 */
data class DirectoryEntryEntity(
    val directoryId: String,
    val editionId: String,
    val newspaperName: String,
    val websiteUrl: String,
    val primaryCategory: String,
    val division: String? = null,
    val district: String? = null,
    val isLiveVerified: Boolean = false
)

/**
 * RSS/Atom feed endpoints maintained separately in the Feed Registry.
 * Example: feedId = "feed_prothom_alo_bn", editionId = "ed_prothom_alo_bn_bd"
 */
data class FeedSourceEntity(
    val feedId: String,
    val editionId: String,
    val feedUrl: String,
    val baseUrl: String,
    val isInternational: Boolean = false,
    val defaultCategory: String = "all"
)
