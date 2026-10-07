-- Destructive fixture replacement. SQL guards also protect direct psql invocation.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL timezone = 'UTC';
SET LOCAL lock_timeout = '3s';
SELECT pg_advisory_xact_lock(85, 1);
CREATE TEMP TABLE perf_config ON COMMIT DROP AS
SELECT :'profile'::text AS profile, :'as_of'::date AS as_of,
       :'schema_checksum'::text AS schema_checksum,
       CASE :'profile' WHEN 'growth' THEN 50000 WHEN 'large' THEN 200000 END AS games,
       CASE :'profile' WHEN 'growth' THEN 200000 WHEN 'large' THEN 2000000 END AS releases,
       CASE :'profile' WHEN 'growth' THEN 500000 WHEN 'large' THEN 5000000 END AS ratings;

DO $$
BEGIN
    IF current_database() <> 'videogame_performance' THEN
        RAISE EXCEPTION 'Refusing a non-performance database';
    END IF;
    IF NOT EXISTS (SELECT FROM performance_data.boundary b JOIN perf_config c
        ON b.schema_checksum=c.schema_checksum WHERE b.purpose='disposable-performance-only') THEN
        RAISE EXCEPTION 'Missing disposable marker or changed schema; stop and regenerate';
    END IF;
    IF EXISTS (SELECT FROM perf_config WHERE games IS NULL OR as_of NOT BETWEEN DATE '2000-01-01' AND DATE '2099-12-31') THEN
        RAISE EXCEPTION 'Unsupported synthetic profile or reference date';
    END IF;
END $$;

-- All fixture-owned application tables together, without CASCADE into another schema.
DO $$
DECLARE tables text;
BEGIN
    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ' ORDER BY schemaname, tablename)
    INTO tables FROM pg_tables WHERE schemaname IN ('catalogue','ratings');
    EXECUTE 'TRUNCATE TABLE ' || tables;
END $$;

CREATE TEMP TABLE perf_game ON COMMIT DROP AS
SELECT n, md5('performance:game:' || n)::uuid AS game_id,
       CASE WHEN c.profile='growth' THEN (ARRAY[1,2,3,10])[n % 4 + 1]
            ELSE (ARRAY[1,3,11,25])[n % 4 + 1] END AS release_count
FROM perf_config c CROSS JOIN LATERAL generate_series(1,c.games) n;
ALTER TABLE perf_game ADD PRIMARY KEY (n);
ANALYZE perf_game;

INSERT INTO catalogue.platform (platform_id,code,display_name) VALUES
    (md5('performance:platform:0')::uuid,'windows-pc','Windows PC'),
    (md5('performance:platform:1')::uuid,'playstation-5','PlayStation 5'),
    (md5('performance:platform:2')::uuid,'xbox-series','Xbox Series X|S'),
    (md5('performance:platform:3')::uuid,'nintendo-switch-2','Nintendo Switch 2'),
    (md5('performance:platform:4')::uuid,'mac','Mac'),
    (md5('performance:platform:5')::uuid,'linux','Linux'),
    (md5('performance:platform:6')::uuid,'switch','Nintendo Switch'),
    (md5('performance:platform:7')::uuid,'ps4-1','PlayStation 4'),
    (md5('performance:platform:8')::uuid,'android','Android'),
    (md5('performance:platform:9')::uuid,'ios','iOS');
INSERT INTO catalogue.region (region_id,code,display_name) VALUES
    (md5('performance:region:0')::uuid,'worldwide','Worldwide'),
    (md5('performance:region:1')::uuid,'europe','Europe'),
    (md5('performance:region:2')::uuid,'north-america','North America'),
    (md5('performance:region:3')::uuid,'japan','Japan'),
    (md5('performance:region:4')::uuid,'unknown','Unknown');
INSERT INTO catalogue.catalogue_publication
    (publication_id,catalogue_version,published_at,last_synchronized_at,source_kind,source_name,is_current)
SELECT md5('performance:revision')::uuid, 'synthetic-' || profile || '-' || as_of,
       as_of::timestamptz, as_of::timestamptz,'product_curated','Synthetic performance dataset',true
FROM perf_config;
INSERT INTO catalogue.game (game_id,created_at)
SELECT game_id, as_of::timestamptz - interval '2 years' FROM perf_game CROSS JOIN perf_config;
INSERT INTO catalogue.game_snapshot
    (publication_id,game_id,canonical_title,slug,cover_reference,cover_source,
     cover_usage_mode,cover_alternative_text,cover_usage_status,summary_text,summary_language)
SELECT md5('performance:revision')::uuid, game_id,
       'Adventure ' || CASE WHEN n % 1000=1 THEN 'Needle ' ELSE '' END ||
       CASE WHEN n % 10=2 THEN 'Élite ' ELSE 'Chronicles ' END || lpad(n::text,6,'0'),
       'performance-game-' || n, '/assets/covers/fallback.svg','Synthetic performance dataset',
       'product_owned','Synthetic performance game ' || n,'approved',
       repeat('Synthetic game summary. ', 10 + n % 40),'en'
FROM perf_game;
INSERT INTO catalogue.game_alias
    (publication_id,game_id,alias,alias_kind,language_tag,approval_status,source_kind,source_name)
SELECT md5('performance:revision')::uuid, game_id,'Aliasquest ' || lpad(n::text,6,'0'),
       'product_curated','en','approved','product_curated','Synthetic performance dataset'
FROM perf_game WHERE n % 5=1;

-- Maximum 100,000 release rows/statement at large; FK trigger queues stay bounded.
DO $$
DECLARE batch_start integer; total_games integer;
BEGIN
    SELECT games INTO total_games FROM perf_config;
    FOR batch_start IN SELECT generate_series(1,total_games,10000) LOOP
        INSERT INTO catalogue.game_release (release_id,game_id,created_at)
        SELECT md5('performance:release:' || n || ':' || slot)::uuid, game_id,
               as_of::timestamptz - interval '2 years'
        FROM perf_game CROSS JOIN perf_config CROSS JOIN LATERAL generate_series(1,release_count) slot
        WHERE n BETWEEN batch_start AND batch_start+9999;

        INSERT INTO catalogue.release_snapshot
            (publication_id,release_id,game_id,platform_id,region_id,date_precision,
             exact_date,release_year,release_month,release_quarter,release_status,
             source_kind,source_name,source_entity_type,last_synchronized_at,
             verification_level,review_status,release_stage)
        WITH shape AS (
            SELECT g.*,slot,c.as_of,
                CASE WHEN slot=1 THEN 'day'
                     WHEN (n*37+slot*11)%100 < 82 THEN 'day'
                     WHEN (n*37+slot*11)%100 < 84 THEN 'month'
                     WHEN (n*37+slot*11)%100 < 90 THEN 'quarter'
                     WHEN (n*37+slot*11)%100 < 98 THEN 'year' ELSE 'unknown' END AS precision,
                -- Past slot #1 is outside the other slots' two-year date band, avoiding
                -- duplicate day tuples while proving rating eligibility independently.
                CASE WHEN slot=1 AND n%5<>0 THEN as_of-1095-n%365
                     WHEN slot=1 THEN as_of+1+n%180
                     WHEN n%5=0 THEN as_of+365+(n*13+slot*97)%365
                     ELSE as_of-730+(n*13+slot*97)%1461 END AS release_date
            FROM perf_game g CROSS JOIN perf_config c CROSS JOIN LATERAL generate_series(1,release_count) slot
            WHERE n BETWEEN batch_start AND batch_start+9999
        )
        SELECT md5('performance:revision')::uuid, md5('performance:release:' || n || ':' || slot)::uuid,
               game_id, md5('performance:platform:' || CASE WHEN precision<>'day' THEN (slot-1+n)%4
                    WHEN (n*17+slot*37)%1000 < 530 THEN 0
                    WHEN (n*17+slot*37)%1000 < 615 THEN 4
                    WHEN (n*17+slot*37)%1000 < 685 THEN 5
                    WHEN (n*17+slot*37)%1000 < 770 THEN 6
                    WHEN (n*17+slot*37)%1000 < 825 THEN 1
                    WHEN (n*17+slot*37)%1000 < 865 THEN 2
                    WHEN (n*17+slot*37)%1000 < 895 THEN 7
                    WHEN (n*17+slot*37)%1000 < 915 THEN 3
                    WHEN (n*17+slot*37)%1000 < 950 THEN 8 ELSE 9 END)::uuid,
               md5('performance:region:' || CASE WHEN n%100<96 THEN 0 ELSE n%100-95 END)::uuid,
               precision, CASE WHEN precision='day' THEN release_date END,
               CASE WHEN precision IN ('month','quarter','year') THEN extract(year FROM release_date)::smallint END,
               CASE WHEN precision='month' THEN extract(month FROM release_date)::smallint END,
               CASE WHEN precision='quarter' THEN extract(quarter FROM release_date)::smallint END,
               CASE WHEN slot=1 THEN 'announced' WHEN (n+slot)%100 < 3 THEN 'cancelled'
                    WHEN (n+slot)%100 < 10 THEN 'delayed' ELSE 'announced' END,
               'product_curated','Synthetic performance dataset','game_release',as_of::timestamptz,
               'provider_only',CASE WHEN slot<>1 AND (n+slot)%50=0 THEN 'required' ELSE 'not_required' END,
               CASE WHEN slot=1 THEN 'full_release' ELSE
                    (ARRAY['alpha','beta','advance_access','early_access','unknown',
                           'full_release','full_release','full_release','full_release','full_release',
                           'full_release','full_release','full_release','full_release','full_release',
                           'full_release','full_release','full_release','full_release','full_release'])[(n+slot)%20+1] END
        FROM shape;
    END LOOP;
END $$;

INSERT INTO catalogue.genre (genre_id,code,display_name)
SELECT md5('performance:genre:' || n)::uuid,'performance-genre-' || n,'Synthetic genre ' || n
FROM generate_series(1,12) n;
INSERT INTO catalogue.game_genre (game_id,genre_id)
SELECT game_id,md5('performance:genre:' || (1+(n+slot)%12))::uuid
FROM perf_game CROSS JOIN generate_series(0,1) slot;
INSERT INTO catalogue.company (company_id,display_name)
SELECT md5('performance:company:' || n)::uuid,'Synthetic company ' || n FROM generate_series(1,100) n;
INSERT INTO catalogue.game_company (game_id,company_role,company_id)
SELECT game_id,role,md5('performance:company:' || (1+n%100))::uuid
FROM perf_game CROSS JOIN (VALUES ('developer'),('publisher')) roles(role);
INSERT INTO catalogue.game_mode (game_mode_id,code,display_name) VALUES
    (md5('performance:mode')::uuid,'single-player','Single player');
INSERT INTO catalogue.game_game_mode (game_id,game_mode_id)
SELECT game_id,md5('performance:mode')::uuid FROM perf_game;
INSERT INTO catalogue.game_featured_evidence
    (game_id,hypes,first_release_date,eligible_product,source_name,observed_at)
SELECT r.game_id,1+g.n%1000,min(r.period_start),true,'Synthetic performance dataset',c.as_of::timestamptz
FROM catalogue.release_snapshot r JOIN perf_game g USING (game_id) CROSS JOIN perf_config c
WHERE r.release_stage='full_release' AND r.release_status='announced' AND r.period_start IS NOT NULL
GROUP BY r.game_id,g.n,c.as_of;

-- Keep Ratings' rebuildable projection coherent with Catalogue, including aliases/genres.
INSERT INTO ratings.game_listing
    (game_id,slug,canonical_title,normalized_title,cover_kind,cover_reference,
     cover_alternative_text,cover_attribution,genres_projected)
SELECT game_id,slug,canonical_title,normalized_title,'product',cover_reference,
       cover_alternative_text,cover_source,true FROM catalogue.game_snapshot;
INSERT INTO ratings.game_listing_alias (game_id,normalized_alias)
SELECT game_id,normalized_alias FROM catalogue.game_alias WHERE approval_status='approved';
INSERT INTO ratings.genre_label (genre_code,display_name)
SELECT code,display_name FROM catalogue.genre;
INSERT INTO ratings.game_listing_genre (game_id,genre_code,position)
SELECT game_id,code,row_number() OVER (PARTITION BY game_id ORDER BY lower(display_name),code)
FROM catalogue.game_genre JOIN catalogue.genre USING (genre_id);

-- Only games with a past, full-release, not-required slot #1 enter the rating pool.
CREATE TEMP TABLE perf_eligible ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY n)::integer AS rank,game_id FROM perf_game WHERE n%5<>0;
ALTER TABLE perf_eligible ADD PRIMARY KEY (rank);
ANALYZE perf_eligible;
CREATE TEMP TABLE perf_user ON COMMIT DROP AS
SELECT n,CASE WHEN c.profile='growth' THEN 100 ELSE 250 END AS history
FROM perf_config c CROSS JOIN LATERAL generate_series(1,CASE WHEN profile='growth' THEN 4000 ELSE 16000 END) n
UNION ALL
SELECT CASE WHEN profile='growth' THEN 4000 ELSE 16000 END+n,
       CASE WHEN profile='growth' THEN 5000 ELSE 10000 END
FROM perf_config c CROSS JOIN LATERAL generate_series(1,CASE WHEN profile='growth' THEN 20 ELSE 100 END) n;

-- One bounded statement per user (100..10,000 ratings), rather than a 5M-row FK queue.
DO $$
DECLARE u record; eligible_count integer;
BEGIN
    SELECT count(*) INTO eligible_count FROM perf_eligible;
    FOR u IN SELECT * FROM perf_user ORDER BY n LOOP
        INSERT INTO ratings.rating (user_id,game_id,value,created_at,updated_at,version_token)
        SELECT md5('performance:user:' || u.n)::uuid,e.game_id,
               (ARRAY[1,2,3,4,5,6,6,7,7,7,8,8,8,9,9,10,10,10,10,10])[(u.n*7+slot*3)%20+1],
               c.as_of::timestamptz-interval '400 days',
               c.as_of::timestamptz-((u.n+slot)%90)*interval '1 day',
               md5('performance:version:' || u.n || ':' || e.game_id)::uuid
        FROM perf_config c CROSS JOIN LATERAL generate_series(1,u.history) slot
        JOIN perf_eligible e ON e.rank = CASE WHEN slot <= least(u.history/5,200)
            THEN 1+(u.n*37+slot*13)%1000
            ELSE 1001+(u.n*997+slot*101)%(eligible_count-1000) END;
    END LOOP;
END $$;

-- Counts and semantic invariants supplement the unchanged CHECK/FK/UNIQUE constraints.
DO $$
DECLARE c record;
BEGIN
    SELECT * INTO c FROM perf_config;
    IF (SELECT count(*) FROM catalogue.game) <> c.games
        OR (SELECT count(*) FROM catalogue.game_snapshot) <> c.games
        OR (SELECT count(*) FROM catalogue.game_release) <> c.releases
        OR (SELECT count(*) FROM catalogue.release_snapshot) <> c.releases
        OR (SELECT count(*) FROM ratings.rating) <> c.ratings
        OR (SELECT count(*) FROM ratings.game_listing WHERE genres_projected) <> c.games
        OR (SELECT count(*) FROM catalogue.catalogue_publication WHERE is_current) <> 1 THEN
        RAISE EXCEPTION 'Synthetic profile cardinality/projection mismatch';
    END IF;
    IF EXISTS (SELECT FROM (SELECT game_id FROM ratings.rating GROUP BY game_id) r WHERE NOT EXISTS (
        SELECT FROM catalogue.release_snapshot s WHERE s.game_id=r.game_id
        AND s.publication_id=md5('performance:revision')::uuid
        AND s.date_precision='day' AND s.exact_date<=c.as_of AND s.release_stage='full_release'
        AND s.release_status='announced' AND s.review_status='not_required')) THEN
        RAISE EXCEPTION 'Rating without eligible release evidence';
    END IF;
    IF EXISTS (SELECT FROM catalogue.release_snapshot GROUP BY game_id HAVING count(*)>25) THEN
        RAISE EXCEPTION 'Game release context exceeds the supported acquisition bound';
    END IF;
    IF NOT EXISTS (SELECT FROM ratings.game_listing g JOIN ratings.rating r USING (game_id)
        WHERE g.title_search_vector @@ to_tsquery('simple','needle:*'))
        OR NOT EXISTS (SELECT FROM ratings.game_listing g JOIN ratings.rating r USING (game_id)
        WHERE g.title_search_vector @@ to_tsquery('simple','elite:*')) THEN
        RAISE EXCEPTION 'Selective/accent-normalized title cohorts must include rated Games';
    END IF;
END $$;
UPDATE performance_data.boundary SET profile=c.profile,reference_date=c.as_of FROM perf_config c;
COMMIT;
-- Retain normal schema indexes/constraints and collect planner statistics before later testing.
ANALYZE;
