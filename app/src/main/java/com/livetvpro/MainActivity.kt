package com.livetvpro

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
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
    
    private var drawerToggle: ActionBarDrawerToggle? = null
    private var currentSearchQuery: String = ""
    private var isSearchVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            Timber.d("MainActivity onCreate started")
            
            // Apply saved theme
            val isDarkTheme = preferencesManager.isDarkTheme()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
                else AppCompatDelegate.MODE_NIGHT_NO
            )

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            Timber.d("Binding inflated successfully")

            setupToolbar()
            setupNavigation()
            setupDrawer()
            setupSearch()
            
            Timber.d("MainActivity onCreate completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "FATAL: Error in MainActivity onCreate")
            e.printStackTrace()
            
            Toast.makeText(
                this,
                "Error starting app: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupToolbar() {
        try {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            Timber.d("Toolbar setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up toolbar")
            throw e
        }
    }

    private fun setupNavigation() {
        try {
            Timber.d("Setting up navigation")
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                
            if (navHostFragment == null) {
                Timber.e("NavHostFragment not found!")
                Toast.makeText(this, "Navigation error - Fragment not found", Toast.LENGTH_LONG).show()
                return
            }
            
            val navController = navHostFragment.navController

            // Top-level destinations where hamburger menu should show
            val topLevelDestinations = setOf(
                R.id.homeFragment,
                R.id.liveEventsFragment,
                R.id.contactFragment
            )

            // Setup drawer navigation
            binding.navigationView.setNavigationItemSelectedListener { menuItem ->
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

            // ✅ FIXED: Update hamburger/back button based on destination
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
                
                // Check if current destination is top-level
                val isTopLevel = destination.id in topLevelDestinations
                
                // ✅ CRITICAL FIX: Properly sync drawer toggle state
                if (isTopLevel) {
                    // Show hamburger menu (drawer indicator)
                    drawerToggle?.isDrawerIndicatorEnabled = true
                    drawerToggle?.syncState()
                    
                    // Enable drawer swipe
                    binding.drawerLayout.setDrawerLockMode(
                        androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                    )
                    
                    // Remove custom navigation click listener
                    binding.toolbar.setNavigationOnClickListener(null)
                } else {
                    // Show back arrow
                    drawerToggle?.isDrawerIndicatorEnabled = false
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    
                    // Disable drawer swipe
                    binding.drawerLayout.setDrawerLockMode(
                        androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                    )
                    
                    // ✅ Set custom back navigation
                    binding.toolbar.setNavigationOnClickListener {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
                
                // Update bottom navigation selection
                if (isTopLevel) {
                    binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
                }
                
                // Close drawer after navigation
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                }
                
                // Hide search when navigating
                if (isSearchVisible) {
                    hideSearch()
                }
            }

            // Favorites button
            binding.btnFavorites.setOnClickListener {
                if (navController.currentDestination?.id != R.id.favoritesFragment) {
                    navController.navigate(R.id.favoritesFragment)
                }
            }
            
            Timber.d("Navigation setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up navigation")
            Toast.makeText(this, "Navigation setup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupDrawer() {
        try {
            drawerToggle = ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
            ).apply {
                isDrawerIndicatorEnabled = true
                // ✅ Enable animated icon transformation
                isDrawerSlideAnimationEnabled = true
                syncState()
            }
            
            drawerToggle?.let {
                binding.drawerLayout.addDrawerListener(it)
            }
            
            Timber.d("Drawer setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up drawer")
            Toast.makeText(this, "Drawer setup failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearch() {
        try {
            // ✅ Initially hide search view
            binding.searchView.visibility = View.GONE
            
            // Search icon click - show search bar
            binding.btnSearch.setOnClickListener {
                showSearch()
            }
            
            // Search view listeners
            binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    currentSearchQuery = query ?: ""
                    // TODO: Implement search in current fragment
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    currentSearchQuery = newText ?: ""
                    // TODO: Implement live search in current fragment
                    return true
                }
            })
            
            // Back button in search mode
            binding.btnSearchBack.setOnClickListener {
                hideSearch()
            }
            
            // Clear search text
            binding.btnSearchClear.setOnClickListener {
                binding.searchView.setQuery("", false)
                currentSearchQuery = ""
            }
            
            Timber.d("Search setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up search")
        }
    }

    private fun showSearch() {
        isSearchVisible = true
        
        // Hide normal toolbar items
        binding.toolbarTitle.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE
        binding.btnFavorites.visibility = View.GONE
        
        // Show search components
        binding.searchView.visibility = View.VISIBLE
        binding.btnSearchBack.visibility = View.VISIBLE
        binding.btnSearchClear.visibility = View.VISIBLE
        
        // Focus and open keyboard
        binding.searchView.isIconified = false
        binding.searchView.requestFocus()
        
        Timber.d("Search mode activated")
    }

    private fun hideSearch() {
        isSearchVisible = false
        
        // Show normal toolbar items
        binding.toolbarTitle.visibility = View.VISIBLE
        binding.btnSearch.visibility = View.VISIBLE
        binding.btnFavorites.visibility = View.VISIBLE
        
        // Hide search components
        binding.searchView.visibility = View.GONE
        binding.btnSearchBack.visibility = View.GONE
        binding.btnSearchClear.visibility = View.GONE
        
        // Clear search
        binding.searchView.setQuery("", false)
        currentSearchQuery = ""
        
        // Close keyboard
        binding.searchView.clearFocus()
        
        Timber.d("Search mode deactivated")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // ✅ FIXED: Let drawer toggle handle home button only if drawer is enabled
        if (drawerToggle?.isDrawerIndicatorEnabled == true) {
            if (drawerToggle?.onOptionsItemSelected(item) == true) {
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHostFragment?.navController
        return navController?.navigateUp() ?: false || super.onSupportNavigateUp()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            // If search is visible, hide it first
            isSearchVisible -> {
                hideSearch()
            }
            // If drawer is open, close it
            binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            // Otherwise, handle normal back
            else -> {
                super.onBackPressed()
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle?.syncState()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle?.onConfigurationChanged(newConfig)
    }

    fun getSearchQuery(): String = currentSearchQuery
}
