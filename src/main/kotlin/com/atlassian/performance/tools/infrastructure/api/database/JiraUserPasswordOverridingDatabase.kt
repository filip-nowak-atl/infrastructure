package com.atlassian.performance.tools.infrastructure.api.database

import com.atlassian.performance.tools.infrastructure.database.SshMysqlClient
import com.atlassian.performance.tools.infrastructure.database.SshSqlClient
import com.atlassian.performance.tools.ssh.api.SshConnection
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.net.URI

class JiraUserPassword(
    val plainText: String,
    val encrypted: String
)

/**
 * Based on https://confluence.atlassian.com/jira/retrieving-the-jira-administrator-192836.html
 *
 * To encode the password use [com.atlassian.crowd.password.encoder.AtlassianSecurityPasswordEncoder](https://docs.atlassian.com/atlassian-crowd/4.2.2/com/atlassian/crowd/password/encoder/AtlassianSecurityPasswordEncoder.html)
 * from the [com.atlassian.crowd.crowd-password-encoders](https://mvnrepository.com/artifact/com.atlassian.crowd/crowd-password-encoders/4.2.2).
 */
class JiraUserPasswordOverridingDatabase internal constructor(
    private val databaseDelegate: Database,
    private val sqlClient: SshSqlClient,
    private val username: String,
    private val userPassword: JiraUserPassword,
    private val jiraDatabaseSchemaName: String
) : Database {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    override fun setup(ssh: SshConnection): String = databaseDelegate.setup(ssh)

    override fun start(
        jira: URI,
        ssh: SshConnection
    ) {
        databaseDelegate.start(jira, ssh)
        if (shouldUseEncryption(ssh)) {
            logger.debug("Updating credential with encrypted password")
            sqlClient.runSql(ssh, "UPDATE ${jiraDatabaseSchemaName}.cwd_user SET credential='${userPassword.encrypted}' WHERE user_name='$username';")
        } else {
            logger.debug("Updating credential with plain text password")
            sqlClient.runSql(ssh, "UPDATE ${jiraDatabaseSchemaName}.cwd_user SET credential='${userPassword.plainText}' WHERE user_name='$username';")
        }
        logger.debug("Password for user '$username' updated to '${userPassword.plainText}'")
    }

    private fun shouldUseEncryption(ssh: SshConnection): Boolean {
        val sqlResult =
            sqlClient.runSql(ssh, "select attribute_value from ${jiraDatabaseSchemaName}.cwd_directory_attribute where attribute_name = 'user_encryption_method';").output
        return when {
            sqlResult.contains("plaintext") -> false
            sqlResult.contains("atlassian-security") -> true
            else -> {
                logger.warn("Unknown user_encryption_method. Assuming encrypted password should be used")
                true
            }
        }
    }

    class Builder(
        private var databaseDelegate: Database,
        private var userPassword: JiraUserPassword
    ) {
        private var sqlClient: SshSqlClient = SshMysqlClient()
        private var jiraDatabaseSchemaName: String = "jiradb"
        private var username: String = "admin"

        fun databaseDelegate(databaseDelegate: Database) = apply { this.databaseDelegate = databaseDelegate }
        fun username(username: String) = apply { this.username = username }
        fun userPassword(userPassword: JiraUserPassword) = apply { this.userPassword = userPassword }
        fun sqlClient(sqlClient: SshSqlClient) = apply { this.sqlClient = sqlClient }
        fun jiraDatabaseSchemaName(jiraDatabaseSchemaName: String) = apply { this.jiraDatabaseSchemaName = jiraDatabaseSchemaName }

        fun build() = JiraUserPasswordOverridingDatabase(
            databaseDelegate = databaseDelegate,
            sqlClient = sqlClient,
            username = username,
            userPassword = userPassword,
            jiraDatabaseSchemaName = jiraDatabaseSchemaName
        )
    }

}

fun Database.withAdminPassword(adminPassword: JiraUserPassword) = JiraUserPasswordOverridingDatabase.Builder(
    databaseDelegate = this,
    userPassword = adminPassword
).build()