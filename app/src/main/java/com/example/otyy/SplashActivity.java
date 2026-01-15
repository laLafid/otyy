package com.example.otyy;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.os.LocaleListCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";
    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_LAST_COUNTRY_CODE = "last_country_code";
    
    private FusedLocationProviderClient fusedLocationClient;
    private final CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

    private final ActivityResultLauncher<String[]> locationPermissionRequest =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean fineLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                Boolean coarseLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                if ((fineLocationGranted != null && fineLocationGranted) || (coarseLocationGranted != null && coarseLocationGranted)) {
                    getLocationAndSetLocale();
                } else {
                    proceedToMain();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        checkLocationPermissionAndFetch();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) {
                proceedToMain();
            }
        }, 12000);
    }

    private void checkLocationPermissionAndFetch() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else {
            getLocationAndSetLocale();
        }
    }

    private void getLocationAndSetLocale() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            proceedToMain();
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.getToken())
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        updateLocaleFromLocation(location.getLatitude(), location.getLongitude());
                    } else {
                        fusedLocationClient.getLastLocation().addOnSuccessListener(lastLocation -> {
                            if (lastLocation != null) {
                                updateLocaleFromLocation(lastLocation.getLatitude(), lastLocation.getLongitude());
                            } else {
                                proceedToMain();
                            }
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Location request failed", e);
                    proceedToMain();
                });
    }

    private void updateLocaleFromLocation(double latitude, double longitude) {
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(latitude, longitude, 1, addresses -> {
                if (!addresses.isEmpty()) {
                    runOnUiThread(() -> processDetectedAddress(addresses.get(0)));
                } else {
                    proceedToMain();
                }
            });
        } else {
            new Thread(() -> {
                try {
                    List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        runOnUiThread(() -> processDetectedAddress(addresses.get(0)));
                    } else {
                        runOnUiThread(this::proceedToMain);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Geocoder error", e);
                    runOnUiThread(this::proceedToMain);
                }
            }).start();
        }
    }

    private void processDetectedAddress(Address address) {
        String currentCountryCode = address.getCountryCode();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastCountryCode = prefs.getString(KEY_LAST_COUNTRY_CODE, "");

        if (currentCountryCode != null && !currentCountryCode.equalsIgnoreCase(lastCountryCode)) {
            showLocationPopup(address);
        } else {
            proceedToMain();
        }
    }

    private void showLocationPopup(Address address) {
        if (isFinishing()) return;

        String countryCode = address.getCountryCode() != null ? address.getCountryCode() : "";
        String countryName = address.getCountryName() != null ? address.getCountryName() : "";
        String cityName = address.getLocality();
        if (cityName == null) cityName = address.getAdminArea();
        
        String flag = getFlagEmoji(countryCode);
        String locationText = (cityName != null ? cityName + ", " : "") + countryName;

        String languageCode = getLanguageForCountry(countryCode);
        String languageName = new Locale(languageCode).getDisplayLanguage();

        // Load the string with HTML tags from resources
        String messageWithHtml = getString(R.string.location_detected_message, locationText, languageName);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.location_detected_title, flag))
                .setMessage(Html.fromHtml(messageWithHtml, Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    saveCountryCode(countryCode);
                    handleLocaleChange(countryCode);
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    saveCountryCode(countryCode);
                    proceedToMain();
                })
                .setCancelable(false)
                .show();
    }

    private void saveCountryCode(String countryCode) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_COUNTRY_CODE, countryCode)
                .apply();
    }

    private void handleLocaleChange(String countryCode) {
        if (countryCode != null) {
            String language = getLanguageForCountry(countryCode);
            LocaleListCompat appLocaleList = LocaleListCompat.forLanguageTags(language);
            AppCompatDelegate.setApplicationLocales(appLocaleList);
        }
        proceedToMain();
    }

    private void proceedToMain() {
        if (!isFinishing()) {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish();
        }
    }

    private String getFlagEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "📍";
        int firstLetter = Character.codePointAt(countryCode.toUpperCase(), 0) - 0x41 + 0x1F1E6;
        int secondLetter = Character.codePointAt(countryCode.toUpperCase(), 1) - 0x41 + 0x1F1E6;
        return new String(Character.toChars(firstLetter)) + new String(Character.toChars(secondLetter));
    }

    private String getLanguageForCountry(String countryCode) {
        switch (countryCode.toUpperCase()) {
            case "ID": return "in";
            case "FR": return "fr";
            case "DE": return "de";
            case "ES": return "es";
            case "JP": return "ja";
            case "KR": return "ko";
            case "CN": return "zh";
            case "BR": return "pt";
            default: return "en";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancellationTokenSource.cancel();
    }
}