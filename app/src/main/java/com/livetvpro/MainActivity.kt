package com.livetvpro

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import com.google.firebase.FirebaseApp
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    @Inject
    lateinit var preferencesManager: PreferencesManager
    
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var currentSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            Timber.d("=== MainActivity onCreate started ===")
            
            // Check Firebase initialization
            try {
                val firebaseApp = FirebaseApp.getInstance()
                Timber.d("✓ Firebase initialized: ${firebaseApp.name}")
                Timber.d("✓ Firebase options: ${firebaseApp.options.projectId}")
            } catch (e: Exception) {
                Timber.e(e, "✗ Firebase NOT initialized!")
                Toast.makeText(this, "Firebase initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            
            // Apply saved theme
            val isDarkTheme = preferencesManager.isDarkTheme()
            Timber.d("Applying theme - Dark mode: $isDarkTheme")
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
                else AppCompatDelegate.MODE_NIGHT_NO
            )

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Timber.d("✓ Binding inflated successfully")

            setupToolbar()
            setupNavigation()
            setupDrawer()
            setupSearch()
            
            Timber.d("=== MainActivity onCreate completed successfully ===")
            
            // Show a toast to confirm the app is running
            Toast.makeText(this, "App loaded successfully", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Timber.e(e, "✗✗✗ FATAL: Error in MainActivity onCreate ✗✗✗")
            e.printStackTrace()
            
            // Show error to user
            Toast.makeText(
                this, 
                "App failed to start: ${e.message}\n\nCheck logcat for details", 
                Toast.LENGTH_LONG
            ).show()
            
            throw e
        }
    }

    private fun setupToolbar() {
        try {
            Timber.d("Setting up toolbar...")
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            Timber.d("✓ Toolbar setup complete")
        } catch (e: Exception) {
            Timber.e(e, "✗ Error setting up toolbar")
            throw e
        }
    }

    private fun setupNavigation() {
        try {
            Timber.d("Setting up navigation...")
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            
            if (navHostFragment == null) {
                Timber.e("✗ NavHostFragment is NULL!")
                Toast.makeText(this, "Navigation setup failed", Toast.LENGTH_LONG).show()
                return
            }
            
            val navController = navHostFragment.navController
            Timber.d("✓ NavController obtained: $navController")

            // Top-level destinations where hamburger menu should show
            val topLevelDestinations = setOf(
                R.id.homeFragment,
                R.id.liveEventsFragment,
                R.id.contactFragment
            )

            // Setup drawer navigation
            binding.navigationView.setNavigationItemSelectedListener { menuItem ->
                Timber.d("Drawer menu item clicked: ${menuItem.title}")
                when (menuItem.itemId) {
                    R.id.homeFragment -> {
                        if (navController.currentDestination?.id != R.id.homeFragment) {
                            if (!navController.popBackStack(R.id.homeFragment, false)) {
                                navController.navigate(R.id.homeFragment)
                            }
                        }
                    }
                    R.id.liveEventsFragment -> {
                        if (navController.currentDestination?.id != R.id.liveEventsFragment) {
                            navController.navigate(R.id.liveEventsFragment)
                        }
                    }
                    R.id.contactFragment -> {
                        if (navController.currentDestination?.id != R.id.contactFragment) {
                            navController.navigate(R.id.contactFragment)
                        }
                    }
                }
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                true
            }

            // Setup bottom navigation
            binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
                Timber.d("Bottom nav item clicked: ${menuItem.title}")
                when (menuItem.itemId) {
                    R.id.homeFragment -> {
                        if (navController.currentDestination?.id != R.id.homeFragment) {
                            if (!navController.popBackStack(R.id.homeFragment, false)) {
                                navController.navigate(R.id.homeFragment)
                            }
                        }
                        true
                    }
                    R.id.liveEventsFragment -> {
                        if (navController.currentDestination?.id != R.id.liveEventsFragment) {
                            navController.navigate(R.id.liveEventsFragment)
                        }
                        true
                    }
                    R.id.contactFragment -> {
                        if (navController.currentDestination?.id != R.id.contactFragment) {
                            navController.navigate(R.id.contactFragment)
                        }
                        true
                    }
                    else -> false
                }
            }

            // Update toolbar title and handle back button vs hamburger menu
            navController.addOnDestinationChangedListener { _, destination, _ ->
                val title = when (destination.id) {
                    R.id.homeFragment -> "Categories"
                    R.id.categoryChannelsFragment -> "Channels"
                    R.id.liveEventsFragment -> "Live Events"
                    R.id.favoritesFragment -> "Favorites"
                    R.id.contactFragment -> "Contact"
                    else -> "Live TV Pro"
                }
                binding.toolbarTitle.text = title
                Timber.d("Navigation: Current destination = $title")
                
                // Show/hide hamburger menu vs back button
                if (destination.id in topLevelDestinations) {
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    supportActionBar?.setDisplayShowHomeEnabled(true)
                    drawerToggle.isDrawerIndicatorEnabled = true
                } else {
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    supportActionBar?.setDisplayShowHomeEnabled(true)
                    drawerToggle.isDrawerIndicatorEnabled = false
                    supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
                }
                
                // Update bottom navigation selection
                if (destination.id in topLevelDestinations) {
                    binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
                }
                
                // Close drawer after navigation
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
            }

            // Favorites button
            binding.btnFavorites.setOnClickListener {
                Timber.d("Favorites button clicked")
                if (navController.currentDestination?.id != R.id.favoritesFragment) {
                    navController.navigate(R.id.favoritesFragment)
                }
            }
            
            Timber.d("✓ Navigation setup complete")
        } catch (e: Exception) {
            Timber.e(e, "✗ Error setting up navigation")
            throw e
        }
    }

    private fun setupDrawer() {
        try {
            Timber.d("Setting up drawer...")
            drawerToggle = ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
            )
            binding.drawerLayout.addDrawerListener(drawerToggle)
            drawerToggle.syncState()
            Timber.d("✓ Drawer setup complete")
        } catch (e: Exception) {
            Timber.e(e, "✗ Error setting up drawer")
            throw e
        }
    }

    private fun setupSearch() {
        try {
            Timber.d("Setting up search...")
            binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    currentSearchQuery = query ?: ""
                    Timber.d("Search query submitted: $currentSearchQuery")
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    currentSearchQuery = newText ?: ""
                    return true
                }
            })
            Timber.d("✓ Search setup complete")
        } catch (e: Exception) {
            Timber.e(e, "✗ Error setting up search")
            throw e
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home && !drawerToggle.isDrawerIndicatorEnabled) {
            onBackPressed()
            return true
        }
        
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    fun getSearchQuery(): String = currentSearchQuery
}
