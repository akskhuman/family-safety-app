package com.personal.familysafety.firebase

import com.google.firebase.database.FirebaseDatabase

object FirebaseConfig {
    const val DATABASE_URL = "https://familysafety-a7e39-default-rtdb.firebaseio.com/"

    fun getDatabase(): FirebaseDatabase {
        return try {
            FirebaseDatabase.getInstance(DATABASE_URL)
        } catch (e: Exception) {
            FirebaseDatabase.getInstance()
        }
    }
}