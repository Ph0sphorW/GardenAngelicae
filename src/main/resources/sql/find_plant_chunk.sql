SELECT *
FROM plants
WHERE world_uuid = ?
  AND chunk_x = ?
  AND chunk_z = ?;