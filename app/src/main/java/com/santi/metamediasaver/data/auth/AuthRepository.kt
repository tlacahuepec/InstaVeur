package com.santi.metamediasaver.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.santi.metamediasaver.data.model.AuthUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

interface AuthRepository {
    val currentUser: Flow<AuthUser?>

    suspend fun signIn(
        email: String,
        password: String,
    )

    suspend fun signUp(
        username: String,
        email: String,
        password: String,
    )

    suspend fun signOut()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseAuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : AuthRepository {
    private val firebaseUserFlow: Flow<FirebaseUser?> =
        callbackFlow {
            val listener =
                FirebaseAuth.AuthStateListener { firebaseAuth ->
                    trySend(firebaseAuth.currentUser)
                }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        }

    override val currentUser: Flow<AuthUser?> =
        firebaseUserFlow.flatMapLatest { user ->
            if (user == null) {
                flowOf(null)
            } else {
                callbackFlow {
                    val registration =
                        profileDocument(user.uid).addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                trySend(user.toAuthUser())
                                return@addSnapshotListener
                            }

                            val username =
                                snapshot?.getString("username")
                                    ?: user.email?.substringBefore('@')
                                    ?: "user"
                            trySend(
                                AuthUser(
                                    uid = user.uid,
                                    email = user.email.orEmpty(),
                                    username = username,
                                ),
                            )
                        }

                    awaitClose { registration.remove() }
                }
            }
        }

    override suspend fun signIn(
        email: String,
        password: String,
    ) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    override suspend fun signUp(
        username: String,
        email: String,
        password: String,
    ) {
        val trimmedEmail = email.trim()
        val authResult = auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
        val user = checkNotNull(authResult.user) { "Firebase did not return a user." }
        val cleanUsername = username.trim().ifBlank { trimmedEmail.substringBefore('@') }

        profileDocument(user.uid).set(
            mapOf(
                "username" to cleanUsername,
                "email" to trimmedEmail,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    private fun profileDocument(uid: String) =
        firestore.collection("users").document(uid)
            .collection("profile").document("main")

    private fun FirebaseUser.toAuthUser(): AuthUser =
        AuthUser(
            uid = uid,
            email = email.orEmpty(),
            username = email?.substringBefore('@') ?: "user",
        )
}
