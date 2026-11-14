-- kind: 'entry' (archive list entry) or 'feed' (RSS <item>); a missing row is re-rendered on the next publish
DROP TABLE item_fragment;
CREATE TABLE item_fragment (
    item_id BIGINT NOT NULL REFERENCES item (id),
    kind VARCHAR(10) NOT NULL,
    html TEXT NOT NULL,
    PRIMARY KEY (item_id, kind)
);
