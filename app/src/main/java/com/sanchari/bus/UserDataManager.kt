package com.sanchari.bus

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import com.sanchari.bus.DatabaseConstants.UserTable
import java.util.UUID

/**
 * Manages all read/write operations for the UserDatabase.
 */
object UserDataManager {

    private const val TAG = "UserDataManager"

    /**
     * Retrieves the current user's info from the UserDatabase.
     * If no user exists, it creates one with a new UUID and empty fields.
     *
     * @param context The application context.
     * @return A User object.
     */
    fun getUser(context: Context): User {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.readableDatabase
        var user: User? = null
        var cursor: Cursor? = null

        try {
            cursor = db.query(
                UserTable.TABLE_NAME,
                arrayOf(
                    UserTable.COLUMN_UUID,
                    UserTable.COLUMN_NAME,
                    UserTable.COLUMN_EMAIL,
                    UserTable.COLUMN_PHONE,
                    UserTable.COLUMN_PLACE
                ),
                null, null, null, null, null, "1" // Limit to 1
            )

            if (cursor.moveToFirst()) {
                val uuid = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_UUID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_NAME))
                val email = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_EMAIL))
                val phone = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_PHONE))
                val place = cursor.getString(cursor.getColumnIndexOrThrow(UserTable.COLUMN_PLACE))
                user = User(uuid, name, email, phone, place)
                Log.i(TAG, "User found: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading user from database", e)
        } finally {
            cursor?.close()
            db.close()
        }

        // If no user was found, create and insert a new record
        return user ?: createNewUser(context)
    }

    /**
     * Creates a new user entry with a unique UUID and empty info.
     * This is called on the first-ever app launch.
     */
    private fun createNewUser(context: Context): User {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase
        val newUuid = UUID.randomUUID().toString()
        val newUser = User(newUuid, "", "", "", "")

        try {
            val values = ContentValues().apply {
                put(UserTable.COLUMN_UUID, newUser.uuid)
                put(UserTable.COLUMN_NAME, newUser.name)
                put(UserTable.COLUMN_EMAIL, newUser.email)
                put(UserTable.COLUMN_PHONE, newUser.phone)
                put(UserTable.COLUMN_PLACE, newUser.place)
            }
            db.insertOrThrow(UserTable.TABLE_NAME, null, values)
            Log.i(TAG, "New user created with UUID: $newUuid")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating new user", e)
        } finally {
            db.close()
        }
        return newUser
    }

    /**
     * Saves the user's information to the database.
     *
     * @param context The application context.
     * @param user The User object to save.
     * @return True if save was successful, false otherwise.
     */
    fun saveUser(context: Context, user: User): Boolean {
        val dbHelper = UserDatabaseHelper(context)
        val db = dbHelper.writableDatabase
        var success = false

        try {
            val values = ContentValues().apply {
                put(UserTable.COLUMN_NAME, user.name)
                put(UserTable.COLUMN_EMAIL, user.email)
                put(UserTable.COLUMN_PHONE, user.phone)
                put(UserTable.COLUMN_PLACE, user.place)
            }

            // We update based on the UUID, which should never change
            val rowsAffected = db.update(
                UserTable.TABLE_NAME,
                values,
                "${UserTable.COLUMN_UUID} = ?",
                arrayOf(user.uuid)
            )

            if (rowsAffected > 0) {
                Log.i(TAG, "User data updated successfully for ${user.uuid}")
                success = true
            } else {
                Log.w(TAG, "Could not update user data. No user found with UUID ${user.uuid}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving user data", e)
        } finally {
            db.close()
        }
        return success
    }

    // TODO: Add functions for reading/writing RecentView table
}

