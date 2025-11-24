package com.livetvpro

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    @Inject
    lateinit var preferencesManager: PreferencesManager
    
    private lateinit var drawerToggle: ActionBarDrawerToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme
        val isDarkTheme = preferencesManager.isDarkTheme()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupNavigation()
        setupDrawer()
        setupToolbarButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // We'll use custom title TextView
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Setup drawer navigation (manual setup for better control)
        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.homeFragment -> {
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        navController.popBackStack(R.id.homeFragment, false)
                            || navController.navigate(R.id.homeFragment)
                    }
                }
                R.id.liveEventsFragment -> {
                    if (navController.currentDestination?.id != R.id.liveEventsFragment) {
                        navController.navigate(R.id.liveEventsFragment)
                    }
                }
                R.id.favoritesFragment -> {
                    if (navController.currentDestination?.id != R.id.favoritesFragment) {
                        navController.navigate(R.id.favoritesFragment)
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

        // Setup bottom navigation (manual setup for better control)
        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.homeFragment -> {
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        navController.popBackStack(R.id.homeFragment, false)
                            || navController.navigate(R.id.homeFragment)
                    }
                    true
                }
                R.id.liveEventsFragment -> {
                    if (navController.currentDestination?.id != R.id.liveEventsFragment) {
                        navController.navigate(R.id.liveEventsFragment)
                    }
                    true
                }
                R.id.favoritesFragment -> {
                    if (navController.currentDestination?.id != R.id.favoritesFragment) {
                        navController.navigate(R.id.favoritesFragment)
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

        // Update toolbar title and bottom nav selection based on destination
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
            
            // Update bottom navigation selection
            binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
            
            // Close drawer after navigation
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    private fun setupDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
    }

    private fun setupToolbarButtons() {
        // Theme toggle button
        binding.btnThemeToggle.setOnClickListener {
            toggleTheme()
        }

        // Search button
        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }

        // Favorites button
        binding.btnFavorites.setOnClickListener {
            // Navigate to favorites
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val navController = navHostFragment.navController
            
            // Check if we're not already on favorites page
            if (navController.currentDestination?.id != R.id.favoritesFragment) {
                navController.navigate(R.id.favoritesFragment)
            }
        }
    }

    private fun showSearchDialog() {
        val searchView = SearchView(this)
        searchView.queryHint = "Search..."
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Search")
            .setView(searchView)
            .setNegativeButton("Close", null)
            .create()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                // Handle search
                dialog.dismiss()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Handle search text change
                return true
            }
        })

        dialog.show()
        
        // Auto-focus search and show keyboard
        searchView.isIconified = false
        searchView.requestFocus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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

    fun toggleTheme() {
        val currentMode = preferencesManager.isDarkTheme()
        val newMode = !currentMode
        preferencesManager.setDarkTheme(newMode)
        
        AppCompatDelegate.setDefaultNightMode(
            if (newMode) AppCompatDelegate.MODE_NIGHT_YES 
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        
        // Recreate activity to apply theme
        recreate()
    }
}
