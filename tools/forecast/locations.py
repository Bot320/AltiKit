"""Training locations: (file name, latitude, longitude, region).

DE and ALPS are the core regions and get extra weight in training; EU covers the rest of the
area the watch is used in (Poland, Czechia, Austria lowlands, Italy, Spain, eastern France).
"""

LOCATIONS = [
    # Germany: coast, lowlands, uplands and summits.
    ("hamburg", 53.55, 9.99, "DE"),
    ("sylt", 54.91, 8.31, "DE"),
    ("warnemuende", 54.18, 12.08, "DE"),
    ("berlin", 52.52, 13.40, "DE"),
    ("hannover", 52.37, 9.73, "DE"),
    ("kassel", 51.31, 9.48, "DE"),
    ("koeln", 50.94, 6.96, "DE"),
    ("frankfurt", 50.11, 8.68, "DE"),
    ("leipzig", 51.34, 12.37, "DE"),
    ("dresden", 51.05, 13.74, "DE"),
    ("nuernberg", 49.45, 11.08, "DE"),
    ("stuttgart", 48.78, 9.18, "DE"),
    ("muenchen", 48.14, 11.58, "DE"),
    ("freiburg", 47.99, 7.85, "DE"),
    ("feldberg", 47.87, 8.00, "DE"),
    ("brocken", 51.80, 10.62, "DE"),
    ("fichtelberg", 50.43, 12.95, "DE"),
    ("arber", 49.11, 13.13, "DE"),
    ("garmisch", 47.49, 11.10, "DE"),
    ("zugspitze", 47.42, 10.98, "DE"),
    # Alps: valleys, resorts and high stations.
    ("oberstdorf", 47.41, 10.28, "ALPS"),
    ("berchtesgaden", 47.63, 13.00, "ALPS"),
    ("innsbruck", 47.27, 11.39, "ALPS"),
    ("salzburg", 47.81, 13.04, "ALPS"),
    ("kitzbuehel", 47.45, 12.39, "ALPS"),
    ("sonnblick", 47.05, 12.96, "ALPS"),
    ("zuerich", 47.38, 8.54, "ALPS"),
    ("saentis", 47.25, 9.34, "ALPS"),
    ("davos", 46.80, 9.84, "ALPS"),
    ("jungfraujoch", 46.55, 7.98, "ALPS"),
    ("zermatt", 46.02, 7.75, "ALPS"),
    ("chamonix", 45.92, 6.87, "ALPS"),
    ("aosta", 45.74, 7.32, "ALPS"),
    ("bolzano", 46.50, 11.35, "ALPS"),
    ("cortina", 46.54, 12.14, "ALPS"),
    # Rest of the area.
    ("strasbourg", 48.57, 7.75, "EU"),
    ("wien", 48.21, 16.37, "EU"),
    ("graz", 47.07, 15.44, "EU"),
    ("praha", 50.08, 14.44, "EU"),
    ("warszawa", 52.23, 21.01, "EU"),
    ("krakow", 50.06, 19.94, "EU"),
    ("gdansk", 54.35, 18.65, "EU"),
    ("kasprowy", 49.23, 19.98, "EU"),
    ("milano", 45.46, 9.19, "EU"),
    ("venezia", 45.44, 12.33, "EU"),
    ("firenze", 43.77, 11.25, "EU"),
    ("roma", 41.90, 12.50, "EU"),
    ("napoli", 40.85, 14.27, "EU"),
    ("madrid", 40.42, -3.70, "EU"),
    ("barcelona", 41.39, 2.17, "EU"),
    ("bilbao", 43.26, -2.93, "EU"),
    ("valencia", 39.47, -0.38, "EU"),
    ("sevilla", 37.39, -5.98, "EU"),
    ("andorra", 42.54, 1.73, "EU"),
    ("sierranevada", 37.09, -3.40, "EU"),
]

# Kept out of training entirely to check that the model generalises to unseen places.
HELD_OUT = {"kassel", "kitzbuehel", "krakow"}
CORE = {"DE", "ALPS"}
