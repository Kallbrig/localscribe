package dev.chaseallbright.localscribe.domain

/**
 * Marks an exception whose message was written for a user and may be displayed verbatim.
 *
 * The default is the opposite: [FailureCopy] hides any message that is not marked. Exception
 * text is written for developers -- users were being shown things like
 * "database or disk is full (code 13 SQLITE_FULL[13])" -- but a blanket ban would also have
 * discarded the messages this app writes deliberately, such as
 * "This file is not a LocalScribe export." This interface is what separates the two.
 */
interface UserFacingMessage

/** Throw when the message is deliberate user copy. See [UserFacingMessage]. */
class UserFacingException(message: String) : Exception(message), UserFacingMessage
