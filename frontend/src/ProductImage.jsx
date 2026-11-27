import { useState } from "react";
import { safeLink } from "./api";
import catalogImages from "./demo-product-images.json";

export default function ProductImage({ item, eager = false, fallback = <span className="image-unavailable">Фото недоступно</span> }) {
  const [failed, setFailed] = useState([]);
  const source = [safeLink(item.image_url), catalogImages[item.offer_id]].find(url => url && !failed.includes(url));
  return source
    ? <img className="product-image" src={source} alt={item.name} loading={eager ? "eager" : "lazy"} decoding="async" onError={() => setFailed(current => [...current, source])} />
    : fallback;
}
