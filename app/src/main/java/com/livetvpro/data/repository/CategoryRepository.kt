package com.livetvpro.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.livetvpro.data.models.Category
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun getCategories(): List<Category> {
        return try {
            Timber.d("Fetching categories from Firestore...")
            
            // Test Firestore connection
            val testQuery = firestore.collection("categories").limit(1).get().await()
            Timber.d("Firestore connection successful. Test doc count: ${testQuery.size()}")
            
            val snapshot = firestore.collection("categories")
                .get()
                .await()
            
            Timber.d("Retrieved ${snapshot.documents.size} category documents")
            
            val categories = snapshot.documents.mapNotNull { doc ->
                try {
                    val category = doc.toObject(Category::class.java)?.copy(id = doc.id)
                    Timber.d("Parsed category: ${category?.name}")
                    category
                } catch (e: Exception) {
                    Timber.e(e, "Error parsing category document: ${doc.id}")
                    null
                }
            }.sortedBy { it.order }
            
            Timber.d("Successfully loaded ${categories.size} categories")
            categories
        } catch (e: Exception) {
            Timber.e(e, "Error loading categories from Firestore")
            Timber.e("Error type: ${e.javaClass.simpleName}")
            Timber.e("Error message: ${e.message}")
            emptyList()
        }
    }

    suspend fun getCategoryBySlug(slug: String): Category? {
        return try {
            Timber.d("Fetching category by slug: $slug")
            val snapshot = firestore.collection("categories")
                .whereEqualTo("slug", slug)
                .get()
                .await()
            val doc = snapshot.documents.firstOrNull() ?: return null
            doc.toObject(Category::class.java)?.copy(id = doc.id)
        } catch (e: Exception) {
            Timber.e(e, "Error loading category by slug: $slug")
            null
        }
    }
}
